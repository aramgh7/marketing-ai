package com.marketingai.service;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.ThreadLocalRandom;

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
    private final JdbcTemplate jdbc;
    private final FunnelService funnelService;

    // ─── Held-call funnel counts (roadmap 8-3; vision §19.5) ─────────────────

    /** Booking + held stage keys for the tenant, or null when the funnel has no appointment quality stage. */
    record HeldStages(String bookingStage, String heldStage) {}

    private HeldStages heldStages(UUID tenantId) {
        String held = funnelService.getQualityStageKey(tenantId).orElse(null);
        if (held == null) return null;
        List<String> booking = jdbc.queryForList(
                "SELECT stage_key FROM funnel_stages WHERE tenant_id = ? " +
                "AND (stage_key = 'BOOKED' OR maps_to_ghl_status LIKE 'AppointmentCreate%') " +
                "ORDER BY ordinal DESC LIMIT 1", String.class, tenantId);
        if (booking.isEmpty()) return null;
        // the quality stage must be an appointment outcome, otherwise the legacy conversion gate applies
        List<String> ghl = jdbc.queryForList(
                "SELECT maps_to_ghl_status FROM funnel_stages WHERE tenant_id = ? AND stage_key = ?",
                String.class, tenantId, held);
        boolean appointment = !ghl.isEmpty() && ghl.get(0) != null && ghl.get(0).startsWith("AppointmentUpdate");
        return appointment ? new HeldStages(booking.get(0), held) : null;
    }

    /** reward_events of sessions attributed to the ad set's creative, for the batch window. */
    private HeldCallGate.Counts heldCounts(MetaAdSet adSet, HeldStages stages) {
        if (adSet.getCreativeId() == null) return new HeldCallGate.Counts(safe(adSet.getClicksTotal()), 0, 0, spend(adSet));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT re.session_id) FILTER (WHERE re.stage_key = ?) AS bookings,
                       COUNT(DISTINCT re.session_id) FILTER (WHERE re.stage_key = ?) AS held
                FROM reward_events re
                JOIN sessions s ON s.id = re.session_id AND s.tenant_id = re.tenant_id
                WHERE re.tenant_id = ? AND s.creative_id = ?
                  AND re.rewarded_at >= COALESCE(?, now() - INTERVAL '90 days')
                """, stages.bookingStage(), stages.heldStage(), adSet.getTenantId(), adSet.getCreativeId(),
                adSet.getBatchLaunchedAt() != null ? java.sql.Timestamp.from(adSet.getBatchLaunchedAt()) : null);
        return new HeldCallGate.Counts(safe(adSet.getClicksTotal()),
                ((Number) row.get("bookings")).longValue(), ((Number) row.get("held")).longValue(), spend(adSet));
    }

    private static HeldCallGate.Counts sum(List<HeldCallGate.Counts> list) {
        long c = 0, b = 0, h = 0; double sp = 0;
        for (HeldCallGate.Counts x : list) { c += x.clicks(); b += x.bookings(); h += x.held(); sp += x.spend(); }
        return new HeldCallGate.Counts(c, b, h, sp);
    }

    private static double spend(MetaAdSet a) {
        return a.getSpendTotal() == null ? 0.0 : a.getSpendTotal();
    }

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
        log.info("Gate 1 — adSet={} impressions={} clicks={} P(better)={}",
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
        log.info("Gate 2 — adSet={} clicks={} P(CTR>group)={}", adSet.getId(), clicks, pBetter);

        if (pBetter < KILL_THRESHOLD) {
            return kill(adSet, String.format("Gate 2: P(CTR>group)=%.2f%% < 10%% (clicks=%d)",
                    pBetter * 100, clicks));
        }

        // Roadmap 8-3: Gate 2 += booking_rate — clicks that never book are not a landing win
        HeldStages stages = heldStages(adSet.getTenantId());
        if (stages != null) {
            HeldCallGate.Counts mine = heldCounts(adSet, stages);
            HeldCallGate.Counts group = sum(peers.stream().map(p -> heldCounts(p, stages)).toList());
            Optional<Double> pBook = HeldCallGate.probBookingRateBetter(mine, group);
            if (pBook.isPresent() && pBook.get() < KILL_THRESHOLD) {
                return kill(adSet, String.format("Gate 2: P(booking_rate>group)=%.2f%% < 10%% (bookings=%d of %d clicks)",
                        pBook.get() * 100, mine.bookings(), mine.clicks()));
            }
        }

        adSet.setGateStatus("GATE_3");
        adSetRepo.save(adSet);
        return true;
    }

    // ─── Gate 3: Conversion ───────────────────────────────────────────────

    private boolean evaluateGate3(MetaAdSet adSet, List<MetaAdSet> peers, MetaAdSet anchor) {
        // Roadmap 8-3: for an appointment funnel Gate 3 is cost per CALL_HELD vs anchor + show-rate kill
        HeldStages stages = heldStages(adSet.getTenantId());
        if (stages != null) {
            HeldCallGate.Counts mine = heldCounts(adSet, stages);
            if (!HeldCallGate.hasEnoughData(mine)) return false;
            HeldCallGate.Counts base = (anchor != null && !anchor.getId().equals(adSet.getId()))
                    ? heldCounts(anchor, stages)
                    : sum(peers.stream().filter(p -> !p.getId().equals(adSet.getId())).map(p -> heldCounts(p, stages)).toList());
            adSet.setGate3EvaluatedAt(Instant.now());
            Optional<String> killReason = HeldCallGate.evaluateGate3(mine, base);
            log.info("Gate 3 (held calls) — adSet={} bookings={} held={} cost/held={} kill={}",
                    adSet.getId(), mine.bookings(), mine.held(), mine.costPerHeld(), killReason.orElse("no"));
            if (killReason.isPresent()) return kill(adSet, killReason.get());
            if (mine.held() >= HeldCallGate.MIN_HELD_FOR_COST) {
                adSet.setGateStatus("PASSED");
                adSetRepo.save(adSet);
                log.info("Gate 3 PASSED on held calls: adSet={} is a winner candidate", adSet.getId());
                return true;
            }
            return false;
        }

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
        log.info("Gate 3 — adSet={} conversions={} P(CVR>baseline)={}",
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
        // Replace: Random rng = new Random();
        // With: ThreadLocalRandom (thread-safe, no shared state)
        Random rng = ThreadLocalRandom.current();
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
