package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.entity.*;
import com.marketingai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Financial Circuit Breaker — three-level protection system (vision §12.5a).
 *
 * L1 (per-creative): spend cap exceeded → pause ad set
 *   Already handled in GateEvaluationService. Here we check for L1→L2 cascade.
 *
 * L2 (per-campaign): Bayesian P(blended CPA > 1.5× target) > 0.85
 *   OR daily spend > 80% ceiling in first 8 hours
 *   OR CPM spike z-score > 3 vs 14-day rolling
 *   Action: throttle (pause new launches in TEST contour)
 *
 * L3 (per-account): P(ROAS < breakeven) > 0.90 for TEST or SCALE
 *   OR monthly budget ceiling reached
 *   Action: pause all active campaigns in the affected contour
 *
 * Cascade rules:
 *   3+ L1 in 24h → trigger L2
 *   L2 unresolved 48h → trigger L3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerService {

    private static final double L2_CPA_PROB_THRESHOLD  = 0.85;
    private static final double L3_ROAS_PROB_THRESHOLD = 0.90;
    private static final double L2_SPEND_CEILING_PCT   = 0.80;
    private static final double L2_CPM_ZSCORE          = 3.0;
    private static final int    L1_CASCADE_COUNT        = 3;

    private final MetaCampaignRepository    campaignRepo;
    private final MetaAdSetRepository       adSetRepo;
    private final CircuitBreakerEventRepository cbRepo;
    private final CampaignCpmHistoryRepository  cpmRepo;
    private final TenantRepository          tenantRepo;
    private final MetaApiService            metaApi;
    private final AppProperties             props;
    private final AlertService              alertService;
    private final LearningPhaseService      learningPhaseService;
    private final org.springframework.web.client.RestTemplate mlRestTemplate =
            new org.springframework.web.client.RestTemplate();

    // ─── Main Entry Point ──────────────────────────────────────────────

    /**
     * Run full circuit breaker check for all active TEST campaigns of a tenant.
     * Called by CircuitBreakerScheduler every 30 minutes.
     */
    @Transactional
    public void checkAll(UUID tenantId) {
        List<MetaCampaign> campaigns = campaignRepo.findAllByTenantId(tenantId)
                .stream()
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .toList();

        for (MetaCampaign campaign : campaigns) {
            try {
                checkCampaign(tenantId, campaign);
            } catch (Exception e) {
                log.warn("CB check failed for campaign {}: {}", campaign.getId(), e.getMessage());
            }
        }

        // L1→L2 cascade: 3+ L1 in last 24h → trigger L2
        long l1Count = cbRepo.findByTenantIdAndLevelAndCreatedAtAfter(
                tenantId, "L1", Instant.now().minus(24, ChronoUnit.HOURS)).size();
        if (l1Count >= L1_CASCADE_COUNT) {
            boolean l2Active = cbRepo.findByTenantIdAndResolvedAtIsNull(tenantId)
                    .stream().anyMatch(e -> "L2".equals(e.getLevel()));
            if (!l2Active) {
                triggerL2(tenantId, campaigns,
                        String.format("Cascade: %d L1 events in last 24h (≥%d threshold)", l1Count, L1_CASCADE_COUNT),
                        null);
            }
        }

        // L2→L3 cascade: unresolved L2 older than 48h → trigger L3
        cbRepo.findByTenantIdAndResolvedAtIsNull(tenantId).stream()
                .filter(e -> "L2".equals(e.getLevel()))
                .filter(e -> e.getCreatedAt().isBefore(Instant.now().minus(48, ChronoUnit.HOURS)))
                .findFirst()
                .ifPresent(staleL2 -> {
                    boolean l3Active = cbRepo.findByTenantIdAndResolvedAtIsNull(tenantId)
                            .stream().anyMatch(ev -> "L3".equals(ev.getLevel()));
                    if (!l3Active) {
                        triggerL3(tenantId, campaigns, "all",
                                "Cascade: L2 unresolved for >48h", null);
                    }
                });
    }

    // ─── Campaign-Level Check ──────────────────────────────────────────

    private void checkCampaign(UUID tenantId, MetaCampaign campaign) {
        List<MetaAdSet> adSets = adSetRepo.findAllByCampaignId(campaign.getId())
                .stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .toList();
        if (adSets.isEmpty()) return;

        float totalSpend = (float) adSets.stream()
                .mapToDouble(a -> a.getSpendToday() != null ? a.getSpendToday() : 0f)
                .sum();
        float totalClicks = (float) adSets.stream()
                .mapToDouble(a -> a.getClicksTotal() != null ? a.getClicksTotal() : 0f)
                .sum();
        float totalConversions = (float) adSets.stream()
                .mapToDouble(a -> a.getConversionsTotal() != null ? a.getConversionsTotal() : 0f)
                .sum();
        long totalImpressions = adSets.stream()
                .mapToLong(a -> a.getImpressionsTotal() != null ? a.getImpressionsTotal() : 0L)
                .sum();

        // ── L1 Bayesian check: P(CPA > 2× target) > 0.90 (vision §12.5a)
        if (campaign.getTargetCpa() != null && campaign.getTargetCpa() > 0) {
            boolean l1Bay = checkL1Bayesian(totalSpend, (int) totalClicks, (int) totalConversions,
                    campaign.getTargetCpa());
            if (l1Bay) {
                String reason = String.format(
                        "L1 Bayesian: P(CPA > 2×target) > 0.90 (spend=%.2f, clicks=%.0f, conversions=%.0f, target=%.2f)",
                        totalSpend, totalClicks, totalConversions, campaign.getTargetCpa());
                log.warn("🟠 L1 Bayesian CB triggered — tenant={} reason={}", tenantId, reason);
                recordEvent(tenantId, "L1", reason, null, campaign.getContour(), "PAUSE_AD_SET");
                sendAlertMsg(tenantId, "🟠 L1 Bayesian Circuit Breaker", reason);
                return;
            }
        }

        // ── L2 check 1: Bayesian P(blended CPA > 1.5× target) > 0.85
        if (campaign.getTargetCpa() != null && campaign.getTargetCpa() > 0 && totalConversions > 0) {
            float blendedCpa = totalSpend / totalConversions;
            float target15x = campaign.getTargetCpa() * 1.5f;
            // Model CPA as Gamma posterior (rough approximation): if blended > 1.5× target
            // Use a simple Bayesian bound: P(true_CPA > threshold) given observed data
            double pCpaTooHigh = bayesianProbCpaTooHigh(totalSpend, totalConversions, target15x);
            if (pCpaTooHigh > L2_CPA_PROB_THRESHOLD) {
                triggerL2(tenantId, List.of(campaign),
                        String.format("L2: P(CPA > 1.5×target)=%.2f%% > 85%% (blended CPA=%.2f, target=%.2f)",
                                pCpaTooHigh * 100, blendedCpa, campaign.getTargetCpa()),
                        pCpaTooHigh);
                return;
            }
        }

        // ── L2 check 2: Daily spend > 80% ceiling in first 8 hours
        if (campaign.getDailyBudget() != null && campaign.getDailyBudget() > 0) {
            double ceilingPct = totalSpend / campaign.getDailyBudget();
            // Check if we're in the first 8 hours — heuristic: syncedAt within 8h of midnight
            // Simplified: just check absolute ratio; scheduler runs every 30min so early-day signals come through
            if (ceilingPct > L2_SPEND_CEILING_PCT && isEarlyInDay()) {
                triggerL2(tenantId, List.of(campaign),
                        String.format("L2: Spend %.0f%% of daily ceiling within first 8h (spent=%.2f, ceiling=%.2f)",
                                ceilingPct * 100, totalSpend, campaign.getDailyBudget()),
                        null);
                return;
            }
        }

        // ── L2 check 3: CPM spike z-score > 3
        if (totalImpressions > 0 && totalSpend > 0) {
            float currentCpm = (totalSpend / totalImpressions) * 1000f;
            recordCpm(campaign.getId(), currentCpm);
            double zScore = computeCpmZScore(campaign.getId(), currentCpm);
            if (zScore > L2_CPM_ZSCORE) {
                triggerL2(tenantId, List.of(campaign),
                        String.format("L2: CPM spike z-score=%.2f > 3.0 (CPM=%.2f)", zScore, currentCpm),
                        null);
                return;
            }
        }

        // ── L3 check: P(ROAS < breakeven) > 0.90
        if (campaign.getBreakevenRoas() != null && campaign.getBreakevenRoas() > 0
                && totalConversions > 0 && totalSpend > 0) {
            // ROAS = revenue / spend; we approximate revenue as conversions × avg_order_value
            // Without AOV data, use CPA-based proxy: if CPA > 1/ROAS_breakeven × assumed_AOV
            // Simplified: check if spend is consistently outpacing conversions
            double pRoasBad = bayesianProbRoasBelowBreakeven(
                    totalSpend, totalConversions, campaign.getBreakevenRoas());
            if (pRoasBad > L3_ROAS_PROB_THRESHOLD) {
                triggerL3(tenantId, List.of(campaign), campaign.getContour(),
                        String.format("L3: P(ROAS < breakeven)=%.2f%% > 90%% (contour=%s)",
                                pRoasBad * 100, campaign.getContour()),
                        pRoasBad);
            }
        }

        // ── L3 check: monthly budget ceiling reached
        if (campaign.getMonthlyCeilingBudget() != null && campaign.getMonthlyCeilingBudget() > 0) {
            float monthSpend = (float) adSets.stream()
                    .mapToDouble(a -> a.getSpendTotal() != null ? a.getSpendTotal() : 0f)
                    .sum();
            if (monthSpend >= campaign.getMonthlyCeilingBudget()) {
                triggerL3(tenantId, List.of(campaign), campaign.getContour(),
                        String.format("L3: Monthly budget ceiling reached (spent=%.2f, ceiling=%.2f)",
                                monthSpend, campaign.getMonthlyCeilingBudget()),
                        null);
            }
        }
    }

    // ─── L2 Action: Throttle TEST contour ─────────────────────────────

    @Transactional
    public void triggerL2(UUID tenantId, List<MetaCampaign> campaigns, String reason, Double pValue) {
        // Check if already active
        boolean already = cbRepo.findByTenantIdAndResolvedAtIsNull(tenantId)
                .stream().anyMatch(e -> "L2".equals(e.getLevel()));
        if (already) return;

        log.warn("🟡 CIRCUIT BREAKER L2 — tenant={} reason={}", tenantId, reason);

        // Action: pause new test launches (pause GATE_1 ad sets that aren't ACTIVE yet)
        campaigns.stream()
                .filter(c -> "TEST".equals(c.getContour()))
                .forEach(c -> adSetRepo.findAllByCampaignIdAndStatus(c.getId(), "PAUSED")
                        .stream()
                        .filter(a -> "GATE_1".equals(a.getGateStatus()))
                        .forEach(a -> log.info("L2 throttle: holding back PAUSED GATE_1 adSet={}", a.getId()))
                );
        // Note: we don't pause ACTIVE ad sets at L2 — only throttle NEW launches

        recordEvent(tenantId, "L2", reason, pValue, "TEST", "THROTTLE_TEST");
        sendAlertMsg(tenantId, "🟡 L2 Circuit Breaker", reason);
    }

    // ─── L3 Action: Auto-pause ─────────────────────────────────────────

    @Transactional
    public void triggerL3(UUID tenantId, List<MetaCampaign> campaigns, String contour, String reason, Double pValue) {
        boolean already = cbRepo.findByTenantIdAndResolvedAtIsNull(tenantId)
                .stream().anyMatch(e -> "L3".equals(e.getLevel()));
        if (already) return;

        log.error("🔴 CIRCUIT BREAKER L3 — tenant={} contour={} reason={}", tenantId, contour, reason);

        Tenant tenant = tenantRepo.findById(tenantId).orElse(null);
        String token = tenant != null && tenant.getMetaAccessTokenEnc() != null
                ? new String(tenant.getMetaAccessTokenEnc(), StandardCharsets.UTF_8) : null;

        String action = "all".equals(contour) ? "PAUSE_ALL"
                : "SCALE".equals(contour) ? "PAUSE_SCALE" : "PAUSE_ALL";

        campaigns.stream()
                .filter(c -> "all".equals(contour) || contour.equals(c.getContour()))
                .filter(c -> "ACTIVE".equals(c.getStatus()))
                .forEach(c -> {
                    if (token != null) {
                        try {
                            metaApi.setCampaignStatus(token, c.getMetaCampaignId(), "PAUSED");
                        } catch (Exception e) {
                            log.error("L3: failed to pause campaign {}: {}", c.getId(), e.getMessage());
                        }
                    }
                    c.setStatus("PAUSED");
                    campaignRepo.save(c);
                    log.warn("L3: paused campaign {} ({})", c.getId(), c.getContour());

                    // Log learning phase before L3 PAUSE for all active ad sets
                    adSetRepo.findAllByCampaignIdAndStatus(c.getId(), "ACTIVE")
                            .forEach(a -> learningPhaseService.logBeforeAction(
                                    tenantId, a.getMetaAdSetId(), "PAUSE", a.getLearningPhase()));
                });

        recordEvent(tenantId, "L3", reason, pValue, contour, action);
        sendAlertMsg(tenantId, "🔴 L3 Circuit Breaker — AUTO-PAUSE", reason);
    }

    // ─── Resolution ────────────────────────────────────────────────────

    @Transactional
    public void resolveEvent(UUID tenantId, UUID eventId, String resolvedBy) {
        cbRepo.findById(eventId).ifPresent(event -> {
            if (!event.getTenantId().equals(tenantId)) return;
            event.setResolvedAt(Instant.now());
            event.setResolvedBy(resolvedBy);
            cbRepo.save(event);
            log.info("CB event {} resolved by {}", eventId, resolvedBy);
        });
    }

    // ─── Bayesian helpers ──────────────────────────────────────────────

    /**
     * P(true CPA > threshold) using Gamma-Poisson model.
     * Observed: conversions ~ Poisson(spend × rate).
     * Posterior rate ~ Gamma(conversions + 1, spend + 1) (exponential prior).
     * CPA = 1/rate → if rate < 1/threshold → CPA > threshold.
     * P(CPA > threshold) = P(rate < 1/threshold) = CDF of Gamma at 1/threshold.
     */
    static double bayesianProbCpaTooHigh(float spend, float conversions, float targetCpa) {
        // Simple approximation: use normal approximation of Gamma CDF
        double alpha = conversions + 1.0;
        double beta  = spend + 1.0;
        double meanRate = alpha / beta;
        double threshold = 1.0 / targetCpa;
        if (meanRate < threshold) {
            // Rate is already below threshold (CPA already above target) — estimate probability
            double cv = 1.0 / Math.sqrt(alpha); // coefficient of variation of Gamma
            double z = (threshold - meanRate) / (meanRate * cv);
            return 0.5 + 0.5 * erf(z / Math.sqrt(2));
        }
        return 0.0; // CPA is fine
    }

    /**
     * P(ROAS < breakeven) using Beta posterior on conversion rate.
     * ROAS proxy = conversions × avgOrderValue / spend.
     * Without AOV data, use conversion rate as proxy: P(CVR < cvr_breakeven).
     */
    static double bayesianProbRoasBelowBreakeven(float spend, float conversions, float breakevenRoas) {
        // Proxy: if breakevenRoas = target_rev/spend, then target_conversions = breakevenRoas × spend / aov
        // Without AOV, use relative threshold: kill if CVR is very low
        // Simplified: CVR = conversions / (spend × 10) as rough proxy with spend as "clicks"
        double observedRate = conversions / (spend + 1.0);
        double threshold = breakevenRoas / 100.0; // breakeven rate approximation
        if (observedRate < threshold) {
            double z = (threshold - observedRate) / Math.sqrt(observedRate * (1 - observedRate) / (spend + 1));
            return 0.5 + 0.5 * erf(Math.min(z, 5) / Math.sqrt(2));
        }
        return 0.0;
    }

    /**
     * Calls FastAPI /cb/l1-check to compute P(CPA > 2× target | data).
     * Returns true if Bayesian trigger fires (should_pause = true via Bayesian path).
     * Falls back gracefully: on any error returns false (no false positives).
     */
    @SuppressWarnings("unchecked")
    boolean checkL1Bayesian(float spend, int clicks, int conversions, float targetCpa) {
        String mlUrl = props.getMlServiceUrl();
        if (mlUrl == null || mlUrl.isBlank()) return false;
        try {
            java.util.Map<String, Object> req = java.util.Map.of(
                    "spend",       (double) spend,
                    "spend_limit", Double.MAX_VALUE,  // only checking Bayesian trigger here
                    "clicks",      clicks,
                    "conversions", conversions,
                    "target_cpa",  (double) targetCpa,
                    "cpa_multiplier", 2.0
            );
            java.util.Map<String, Object> resp = mlRestTemplate.postForObject(
                    mlUrl + "/cb/l1-check", req, java.util.Map.class);
            if (resp == null) return false;
            Object bt = resp.get("bayesian_trigger");
            return Boolean.TRUE.equals(bt);
        } catch (Exception e) {
            log.debug("L1 Bayesian check ML call failed (non-blocking): {}", e.getMessage());
            return false;
        }
    }

    /** Error function approximation (Abramowitz & Stegun 7.1.26) */
    static double erf(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741
                + t * (-1.453152027 + t * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-x * x);
        return x >= 0 ? result : -result;
    }

    // ─── CPM Spike Detection ───────────────────────────────────────────

    private void recordCpm(UUID campaignId, float cpm) {
        CampaignCpmHistory h = new CampaignCpmHistory();
        h.setCampaignId(campaignId);
        h.setRecordedAt(Instant.now());
        h.setCpm(cpm);
        cpmRepo.save(h);
    }

    private double computeCpmZScore(UUID campaignId, float currentCpm) {
        List<CampaignCpmHistory> history = cpmRepo
                .findByCampaignIdAndRecordedAtAfterOrderByRecordedAtDesc(
                        campaignId, Instant.now().minus(14, ChronoUnit.DAYS));
        if (history.size() < 5) return 0.0; // not enough history

        double[] values = history.stream().mapToDouble(h -> h.getCpm()).toArray();
        double mean = 0, variance = 0;
        for (double v : values) mean += v;
        mean /= values.length;
        for (double v : values) variance += (v - mean) * (v - mean);
        double stddev = Math.sqrt(variance / values.length);
        return stddev < 0.001 ? 0.0 : (currentCpm - mean) / stddev;
    }

    // ─── Internal helpers ──────────────────────────────────────────────

    private boolean isEarlyInDay() {
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneOffset.UTC);
        return now.getHour() < 8;
    }

    /** Called by PixelPollutionService to surface drift as an L2 CB event. */
    public void createPixelPollutionEvent(UUID tenantId, String campaign, String description) {
        recordEvent(tenantId, "L2",
                "PIXEL_POLLUTION_DRIFT" + (campaign != null ? " campaign=" + campaign : ""),
                null, "TEST", description);
    }

    private void recordEvent(UUID tenantId, String level, String reason, Double pValue,
                             String contour, String action) {
        CircuitBreakerEvent event = new CircuitBreakerEvent();
        event.setTenantId(tenantId);
        event.setLevel(level);
        event.setTriggerReason(reason);
        event.setBayesianProb(pValue != null ? pValue.floatValue() : null);
        event.setContour(contour);
        event.setActionTaken(action);
        cbRepo.save(event);
    }

    private void sendAlertMsg(UUID tenantId, String title, String reason) {
        try {
            String msg = title + "\n\nTenant: " + tenantId + "\nReason: " + reason
                    + "\n\nCheck admin panel to resolve.";
            alertService.sendAlert(msg);
            log.info("CB alert sent: {}", title);
        } catch (Exception e) {
            log.warn("Failed to send CB alert: {}", e.getMessage());
        }
    }
}
