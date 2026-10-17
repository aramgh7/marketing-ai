package com.marketingai.service;

import com.marketingai.entity.MetaAdSet;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.repository.MetaAdSetRepository;
import com.marketingai.repository.MetaCampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Bayesian Staged Gate evaluation per vision §12.3.
 *
 * Three gates with Bayesian stopping rules:
 *   Gate 1 (Hook): ≥5K impressions + ≥2 days → P(CTR > group mean) ≥ 10%
 *   Gate 2 (Click+Landing): ≥300 clicks → P(CTR > group mean) ≥ 10%
 *   Gate 3 (Conversion): ≥50 conversions → P(CVR > anchor CVR) ≥ 10%
 *
 * Kill rule: if P(metric_i > baseline) < KILL_THRESHOLD (0.10) → pause ad set.
 * NOT hard thresholds — Bayesian posterior probabilities (§12.3).
 *
 * L1 circuit breaker: if spend > max_spend_per_adset → kill regardless of gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GateEvaluationService {

    private static final double KILL_THRESHOLD = 0.10; // P < 10% → kill
    private static final int GATE_1_MIN_IMPRESSIONS = 5_000;
    private static final int GATE_2_MIN_CLICKS = 300;
    private static final int GATE_3_MIN_CONVERSIONS = 50;
    private static final long GATE_1_MIN_DAYS = 2;
    private static final int MC_SAMPLES = 10_000;

    private final MetaAdSetRepository adSetRepo;
    private final MetaCampaignRepository campaignRepo;
    private final MetaApiService metaApi;

    /**
     * Evaluate all eligible TEST campaign ad sets for the given tenant.
     * Called by GateScanScheduler (hourly) and manually via GateController.
     *
     * @return number of ad sets evaluated (killed + advanced)
     */
    @Transactional
    public int evaluateAll(UUID tenantId) {
        int count = 0;
        for (MetaCampaign campaign : campaignRepo.findAllByTenantId(tenantId)) {
            if (!"TEST".equals(campaign.getContour())) continue;
            count += evaluateCampaign(campaign);
        }
        return count;
    }

    /**
     * Evaluate all ad sets in a single campaign.
     */
    @Transactional
    public int evaluateCampaign(MetaCampaign campaign) {
        List<MetaAdSet> adSets = adSetRepo.findAllByCampaignId(campaign.getId())
                .stream()
                .filter(a -> !"KILLED".equals(a.getGateStatus()) && !"PASSED".equals(a.getGateStatus()))
                .toList();

        if (adSets.isEmpty()) return 0;

        // Find anchor for Gate 3 comparison
        Optional<MetaAdSet> anchorOpt = adSets.stream().filter(MetaAdSet::isAnchor).findFirst();

        int count = 0;
        for (MetaAdSet adSet : adSets) {
            boolean changed = evaluateAdSet(adSet, campaign, adSets, anchorOpt.orElse(null));
            if (changed) count++;
        }
        return count;
    }

    private boolean evaluateAdSet(MetaAdSet adSet, MetaCampaign campaign,
                                  List<MetaAdSet> batchPeers, MetaAdSet anchor) {
        // L1 circuit breaker: spend cap
        if (campaign.getMaxSpendPerAdset() != null
                && adSet.getSpendTotal() != null
                && adSet.getSpendTotal() >= campaign.getMaxSpendPerAdset()) {
            return kill(adSet, "L1: spend $" + adSet.getSpendTotal()
                    + " exceeded max $" + campaign.getMaxSpendPerAdset());
        }

        return switch (adSet.getGateStatus()) {
            case "GATE_1" -> evaluateGate1(adSet, batchPeers);
            case "GATE_2" -> evaluateGate2(adSet, batchPeers);
            case "GATE_3" -> evaluateGate3(adSet, batchPeers, anchor);
            default -> false;
        };
    }

    // ─── Gate 1: Hook (impressions) ─────────────────────────────────────

    private boolean evaluateGate1(MetaAdSet adSet, List<MetaAdSet> peers) {
        long impressions = safe(adSet.getImpressionsTotal());

        // Entry condition: ≥5K impressions AND ≥2 days since launch
        if (impressions < GATE_1_MIN_IMPRESSIONS) return false;
        if (adSet.getBatchLaunchedAt() != null
                && ChronoUnit.DAYS.between(adSet.getBatchLaunchedAt(), Instant.now()) < GATE_1_MIN_DAYS) {
            return false;
        }

        long clicks = safe(adSet.getClicksTotal());

        // Group mean CTR (all GATE_1 or higher peers)
        long groupClicks = peers.stream().mapToLong(a -> safe(a.getClicksTotal())).sum();
        long groupImpressions = peers.stream().mapToLong(a -> safe(a.getImpressionsTotal())).sum();
        double groupAlpha = groupClicks + 1.0;
        double groupBeta  = Math.max(groupImpressions - groupClicks, 0) + 1.0;

        // Peer posterior for this ad set
        double alpha = clicks + 1.0;
        double beta  = Math.max(impressions - clicks, 0) + 1.0;

        double pBetter = probBetter(alpha, beta, groupAlpha, groupBeta);

        adSet.setGate1EvaluatedAt(Instant.now());
        log.info("Gate 1 — adSet={} impressions={} clicks={} P(better)={:.3f}",
                adSet.getId(), impressions, clicks, pBetter);

        if (pBetter < KILL_THRESHOLD) {
            return kill(adSet, String.format("Gate 1: P(CTR>group)=%.2f%% < 10%% (impressions=%d)",
                    pBetter * 100, impressions));
        }

        // Advance to Gate 2
        adSet.setGateStatus("GATE_2");
        adSetRepo.save(adSet);
        return true;
    }

    // ─── Gate 2: Click + Landing ──────────────────────────────────────────

    private boolean evaluateGate2(MetaAdSet adSet, List<MetaAdSet> peers) {
        long clicks = safe(adSet.getClicksTotal());
        if (clicks < GATE_2_MIN_CLICKS) return false;

        long impressions = safe(adSet.getImpressionsTotal());
        long groupClicks = peers.stream().mapToLong(a -> safe(a.getClicksTotal())).sum();
        long groupImpressions = peers.stream().mapToLong(a -> safe(a.getImpressionsTotal())).sum();

        double alpha = clicks + 1.0;
        double beta  = Math.max(impressions - clicks, 0) + 1.0;
        double groupAlpha = groupClicks + 1.0;
        double groupBeta  = Math.max(groupImpressions - groupClicks, 0) + 1.0;

        double pBetter = probBetter(alpha, beta, groupAlpha, groupBeta);

        adSet.setGate2EvaluatedAt(Instant.now());
        log.info("Gate 2 — adSet={} clicks={} P(CTR>group)={:.3f}", adSet.getId(), clicks, pBetter);

        if (pBetter < KILL_THRESHOLD) {
            return kill(adSet, String.format("Gate 2: P(CTR>group)=%.2f%% < 10%% (clicks=%d)",
                    pBetter * 100, clicks));
        }

        adSet.setGateStatus("GATE_3");
        adSetRepo.save(adSet);
        return true;
    }

    // ─── Gate 3: Conversion ───────────────────────────────────────────────

    private boolean evaluateGate3(MetaAdSet adSet, List<MetaAdSet> peers, MetaAdSet anchor) {
        long conversions = safe(adSet.getConversionsTotal());
        if (conversions < GATE_3_MIN_CONVERSIONS) return false;

        long clicks = safe(adSet.getClicksTotal());

        // Baseline: anchor if available, otherwise group mean
        double baseAlpha, baseBeta;
        if (anchor != null && !anchor.getId().equals(adSet.getId())) {
            long anchorConv = safe(anchor.getConversionsTotal());
            long anchorClicks = safe(anchor.getClicksTotal());
            baseAlpha = anchorConv + 1.0;
            baseBeta  = Math.max(anchorClicks - anchorConv, 0) + 1.0;
        } else {
            long groupConv = peers.stream().mapToLong(a -> safe(a.getConversionsTotal())).sum();
            long groupClicks = peers.stream().mapToLong(a -> safe(a.getClicksTotal())).sum();
            baseAlpha = groupConv + 1.0;
            baseBeta  = Math.max(groupClicks - groupConv, 0) + 1.0;
        }

        double alpha = conversions + 1.0;
        double beta  = Math.max(clicks - conversions, 0) + 1.0;

        double pBetter = probBetter(alpha, beta, baseAlpha, baseBeta);

        adSet.setGate3EvaluatedAt(Instant.now());
        log.info("Gate 3 — adSet={} conversions={} P(CVR>baseline)={:.3f}",
                adSet.getId(), conversions, pBetter);

        if (pBetter < KILL_THRESHOLD) {
            return kill(adSet, String.format("Gate 3: P(CVR>baseline)=%.2f%% < 10%% (conversions=%d)",
                    pBetter * 100, conversions));
        }

        adSet.setGateStatus("PASSED");
        adSetRepo.save(adSet);
        log.info("Gate 3 PASSED: adSet={} is a winner candidate", adSet.getId());
        return true;
    }

    // ─── Bayesian Monte Carlo ─────────────────────────────────────────────

    /**
     * P(θ1 > θ2) where θ1 ~ Beta(a1, b1) and θ2 ~ Beta(a2, b2).
     * Estimated via Monte Carlo (Marsaglia-Tsang Gamma sampler).
     */
    static double probBetter(double a1, double b1, double a2, double b2) {
        Random rng = new Random();
        int wins = 0;
        for (int i = 0; i < MC_SAMPLES; i++) {
            if (sampleBeta(a1, b1, rng) > sampleBeta(a2, b2, rng)) wins++;
        }
        return (double) wins / MC_SAMPLES;
    }

    /**
     * Sample from Beta(alpha, beta) using Marsaglia-Tsang Gamma sampler.
     * Accurate for all alpha, beta > 0.
     */
    static double sampleBeta(double alpha, double beta, Random rng) {
        double g1 = sampleGamma(alpha, rng);
        double g2 = sampleGamma(beta, rng);
        return g1 / (g1 + g2);
    }

    /**
     * Marsaglia & Tsang (2000) Gamma sampler.
     * Works for shape > 0 via the GS/squeeze algorithm.
     */
    static double sampleGamma(double shape, Random rng) {
        if (shape < 1.0) {
            // Reduction: Gamma(a) = Gamma(a+1) * U^(1/a)
            return sampleGamma(shape + 1.0, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x, v;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1.0 - 0.0331 * (x * x) * (x * x)) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) return d * v;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private boolean kill(MetaAdSet adSet, String reason) {
        adSet.setGateStatus("KILLED");
        adSet.setGateKillReason(reason);
        adSet.setGateKillAt(Instant.now());
        adSet.setStatus("PAUSED");
        adSetRepo.save(adSet);
        log.info("KILLED adSet={} reason={}", adSet.getId(), reason);
        return true;
    }

    private static long safe(Long v) {
        return v == null ? 0L : v;
    }
}
