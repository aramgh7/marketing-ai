package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Daily burnout scan 06:00 UTC (Phase 6-1 + v1.1).
 * v1.1: uses survival model when n_obs >= 30; auto-creates brief on 7-day warning;
 * suppresses alert when anchor verdict = MARKET (vision §8.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BurnoutScanScheduler {

    private static final int N_MIN_FOR_SURVIVAL = 30;
    private static final int DAYS_REMAINING_ALERT = 7;

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final AlertService alertService;

    @Scheduled(cron = "0 0 6 * * *")
    public void runDailyScan() {
        List<UUID> tenantIds = jdbc.queryForList("""
                SELECT DISTINCT tenant_id
                FROM meta_insights_history
                WHERE recorded_at > now() - interval '7 days'
                """, UUID.class);

        log.info("Burnout scan: {} tenants", tenantIds.size());
        for (UUID tenantId : tenantIds) {
            try {
                scanTenant(tenantId);
            } catch (Exception e) {
                log.warn("Burnout scan failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void scanTenant(UUID tenantId) {
        List<Map<String, Object>> creatives = jdbc.queryForList("""
                SELECT c.id, c.title AS name,
                       COALESCE(ins.frequency, 1.0)                                  AS frequency,
                       1.0                                                           AS initial_roas,
                       GREATEST(0, EXTRACT(DAY FROM now() - c.created_at))::int      AS days_running,
                       COALESCE(ins.n_obs, 0)                                        AS n_obs
                FROM creatives c
                LEFT JOIN LATERAL (
                    SELECT AVG(mih.frequency) AS frequency, COUNT(*) AS n_obs
                    FROM meta_ad_sets mas
                    JOIN meta_insights_history mih ON mih.ad_set_id = mas.meta_ad_set_id AND mih.tenant_id = mas.tenant_id
                    WHERE mas.creative_id = c.id AND mas.tenant_id = c.tenant_id
                ) ins ON TRUE
                WHERE c.tenant_id = ?
                  AND c.status = 'ACTIVE'
                """, tenantId);

        for (Map<String, Object> row : creatives) {
            UUID creativeId = (UUID) row.get("id");
            int nObs        = ((Number) row.get("n_obs")).intValue();
            double frequency    = ((Number) row.get("frequency")).doubleValue();
            double initialRoas  = ((Number) row.get("initial_roas")).doubleValue();
            int daysRunning     = ((Number) row.get("days_running")).intValue();

            // Check anchor verdict to suppress false burnout alert
            String anchorVerdict = getLatestAnchorVerdict(tenantId, creativeId);

            Map<String, Object> result;
            if (nObs >= N_MIN_FOR_SURVIVAL) {
                result = callSurvivalModel(frequency, initialRoas, daysRunning, nObs, anchorVerdict);
            } else {
                result = callHeuristicModel(frequency, daysRunning);
            }

            if (result == null) continue;

            updateCreativeQuality(tenantId, creativeId, result);

            Boolean alertSuppressed = (Boolean) result.getOrDefault("alert_suppressed", false);
            Integer daysRemaining   = result.get("days_remaining") instanceof Number
                    ? ((Number) result.get("days_remaining")).intValue() : null;

            if (daysRemaining != null && daysRemaining <= DAYS_REMAINING_ALERT && !Boolean.TRUE.equals(alertSuppressed)) {
                UUID briefId = autoCreateReplacementBrief(tenantId, creativeId, (String) row.get("name"), daysRemaining);
                if (briefId != null) {
                    jdbc.update("""
                            UPDATE creative_quality SET replacement_brief_id = ?
                            WHERE creative_id = ? AND tenant_id = ?
                            """, briefId, creativeId, tenantId);
                }
                alertService.sendAlert(String.format(
                        "⚠️ Burnout warning: creative %s predicted to burn out in %d days. Brief auto-created.",
                        row.get("name"), daysRemaining));
            }
        }
    }

    private String getLatestAnchorVerdict(UUID tenantId, UUID creativeId) {
        try {
            List<String> verdicts = jdbc.queryForList("""
                    SELECT verdict FROM anchor_divergence_log adl
                    WHERE adl.tenant_id = ?
                      AND adl.campaign_id IN (SELECT mas.campaign_id FROM meta_ad_sets mas
                                              WHERE mas.creative_id = ? AND mas.tenant_id = adl.tenant_id)
                    ORDER BY adl.created_at DESC LIMIT 1
                    """, String.class, tenantId, creativeId);
            return verdicts.isEmpty() ? null : verdicts.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callSurvivalModel(double frequency, double initialRoas,
                                                   int daysRunning, int nObs, String anchorVerdict) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("frequency",            frequency);
            req.put("initial_roas",         initialRoas);
            req.put("current_days_running", daysRunning);
            req.put("n_obs",                nObs);
            if (anchorVerdict != null) req.put("anchor_verdict", anchorVerdict);
            return restTemplate.postForObject(appProperties.getMlServiceUrl() + "/burnout/survival", req, Map.class);
        } catch (Exception e) {
            log.debug("Survival model call failed, falling back to heuristic: {}", e.getMessage());
            return callHeuristicModel(frequency, daysRunning);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callHeuristicModel(double frequency, int daysRunning) {
        try {
            return restTemplate.postForObject(appProperties.getMlServiceUrl() + "/burnout/score",
                    Map.of("frequency", frequency, "ctr_current", 0.02,
                           "ctr_baseline", 0.03, "spend_current", 0.0, "spend_prev", 0.0,
                           "ctr_drop_rate_per_day", 0.0), Map.class);
        } catch (Exception e) {
            log.warn("Heuristic burnout call failed: {}", e.getMessage());
            return null;
        }
    }

    private void updateCreativeQuality(UUID tenantId, UUID creativeId, Map<String, Object> result) {
        try {
            Integer burnoutDay = result.get("predicted_burnout_day") instanceof Number
                    ? ((Number) result.get("predicted_burnout_day")).intValue() : null;
            String method = (String) result.getOrDefault("method", "HEURISTIC");
            String confidence = (String) result.getOrDefault("confidence", "LOW");
            jdbc.update("""
                    UPDATE creative_quality
                    SET predicted_burnout_day   = ?,
                        burnout_survival_method = ?,
                        burnout_confidence      = ?
                    WHERE creative_id = ? AND tenant_id = ?
                    """, burnoutDay, method, confidence, creativeId, tenantId);
        } catch (Exception e) {
            log.debug("creative_quality update skipped: {}", e.getMessage());
        }
    }

    private UUID autoCreateReplacementBrief(UUID tenantId, UUID creativeId, String creativeName, int daysRemaining) {
        try {
            UUID briefId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO creator_briefs (id, tenant_id, brief_type, creative_type, title, notes, version)
                    VALUES (?, ?, 'VIDEO', 'VIDEO', ?, ?, 1)
                    """,
                    briefId, tenantId,
                    "AUTO: burnout replacement for " + creativeName,
                    String.format("AUTO: burnout predicted in %d days for creative '%s' (id=%s)",
                            daysRemaining, creativeName, creativeId));
            return briefId;
        } catch (Exception e) {
            log.warn("Auto brief creation failed: {}", e.getMessage());
            return null;
        }
    }
}
