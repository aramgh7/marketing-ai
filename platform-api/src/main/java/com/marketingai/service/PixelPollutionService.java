package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pixel Pollution Auto-Detection — Level 3 (vision §6.8).
 *
 * Quality rate = CRM_MEETING_ATTENDED events / FORM_SUBMIT events per UTM campaign.
 * Drift alert fires when:
 *   quality_rate_14d < 0.70 × baseline_rate_30d
 *   AND forms_14d >= 20 (min volume — avoid noise on tiny cohorts)
 *
 * Action: insert pixel_pollution_scans record + CircuitBreaker L2 event + Telegram alert.
 * The CircuitBreaker L2 carries reason="PIXEL_POLLUTION_DRIFT" so the marketer
 * sees it in the existing circuit-breaker dashboard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PixelPollutionService {

    private static final double DRIFT_THRESHOLD   = 0.70;  // quality_rate < 70% of baseline
    private static final int    MIN_FORM_VOLUME   = 20;    // min FORM_SUBMIT count in 14d

    private final JdbcTemplate                 jdbc;
    private final CircuitBreakerService        circuitBreakerService;
    private final AlertService                 alertService;

    public void runScan() {
        List<UUID> tenants = jdbc.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                scanTenant(tenantId);
            } catch (Exception e) {
                log.error("Pixel pollution scan failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    private void scanTenant(UUID tenantId) {
        // Quality rates grouped by utm_campaign (NULL = all campaigns aggregated)
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                WITH forms_14 AS (
                    SELECT utm_campaign, COUNT(*) AS cnt
                    FROM   sessions
                    WHERE  tenant_id   = ?
                      AND  converted   = true
                      AND  started_at  > now() - INTERVAL '14 days'
                    GROUP  BY utm_campaign
                ),
                meetings_14 AS (
                    SELECT s.utm_campaign, COUNT(*) AS cnt
                    FROM   events e
                    JOIN   sessions s ON s.id = e.session_id AND s.tenant_id = e.tenant_id
                    WHERE  e.tenant_id  = ?
                      AND  e.event_type = 'CRM_MEETING_ATTENDED'
                      AND  e.event_time > now() - INTERVAL '14 days'
                    GROUP  BY s.utm_campaign
                ),
                forms_30 AS (
                    SELECT utm_campaign, COUNT(*) AS cnt
                    FROM   sessions
                    WHERE  tenant_id   = ?
                      AND  converted   = true
                      AND  started_at  > now() - INTERVAL '30 days'
                    GROUP  BY utm_campaign
                ),
                meetings_30 AS (
                    SELECT s.utm_campaign, COUNT(*) AS cnt
                    FROM   events e
                    JOIN   sessions s ON s.id = e.session_id AND s.tenant_id = e.tenant_id
                    WHERE  e.tenant_id  = ?
                      AND  e.event_type = 'CRM_MEETING_ATTENDED'
                      AND  e.event_time > now() - INTERVAL '30 days'
                    GROUP  BY s.utm_campaign
                )
                SELECT
                    f14.utm_campaign,
                    f14.cnt                                 AS forms_14d,
                    COALESCE(m14.cnt, 0)                    AS meetings_14d,
                    CASE WHEN f14.cnt > 0
                         THEN COALESCE(m14.cnt, 0)::numeric / f14.cnt
                    END                                     AS quality_rate_14d,
                    CASE WHEN COALESCE(f30.cnt, 0) > 0
                         THEN COALESCE(m30.cnt, 0)::numeric / f30.cnt
                    END                                     AS baseline_rate_30d
                FROM       forms_14  f14
                LEFT JOIN  meetings_14 m14 ON m14.utm_campaign IS NOT DISTINCT FROM f14.utm_campaign
                LEFT JOIN  forms_30    f30 ON f30.utm_campaign IS NOT DISTINCT FROM f14.utm_campaign
                LEFT JOIN  meetings_30 m30 ON m30.utm_campaign IS NOT DISTINCT FROM f14.utm_campaign
                WHERE  f14.cnt >= ?
                """,
                tenantId, tenantId, tenantId, tenantId, MIN_FORM_VOLUME
        );

        for (Map<String, Object> row : rows) {
            String  campaign    = (String)  row.get("utm_campaign");
            int     forms14     = toInt(row.get("forms_14d"));
            int     meetings14  = toInt(row.get("meetings_14d"));
            Double  rate14      = toDouble(row.get("quality_rate_14d"));
            Double  baseline    = toDouble(row.get("baseline_rate_30d"));

            double driftPct = 0.0;
            boolean alertFired = false;

            if (rate14 != null && baseline != null && baseline > 0) {
                driftPct = (rate14 / baseline - 1.0) * 100.0;
                alertFired = rate14 < DRIFT_THRESHOLD * baseline;
            }

            // Upsert scan record (deduplicated by tenant + campaign + date)
            jdbc.update(
                    """
                    INSERT INTO pixel_pollution_scans
                        (tenant_id, utm_campaign, scan_date, forms_14d, meetings_14d,
                         quality_rate_14d, baseline_rate_30d, drift_pct, alert_fired)
                    VALUES (?, ?, CURRENT_DATE, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, COALESCE(utm_campaign, ''), scan_date)
                    DO UPDATE SET
                        forms_14d         = EXCLUDED.forms_14d,
                        meetings_14d      = EXCLUDED.meetings_14d,
                        quality_rate_14d  = EXCLUDED.quality_rate_14d,
                        baseline_rate_30d = EXCLUDED.baseline_rate_30d,
                        drift_pct         = EXCLUDED.drift_pct,
                        alert_fired       = EXCLUDED.alert_fired
                    """,
                    tenantId, campaign, forms14, meetings14,
                    rate14, baseline, driftPct, alertFired
            );

            if (alertFired) {
                fireAlert(tenantId, campaign, forms14, meetings14, rate14, baseline, driftPct);
            }
        }
    }

    private void fireAlert(UUID tenantId, String campaign, int forms14, int meetings14,
                           double rate14, double baseline, double driftPct) {
        String desc = String.format(
                "Pixel pollution drift detected — campaign='%s' quality_rate=%.1f%% (baseline=%.1f%%, drift=%.1f%%)",
                campaign != null ? campaign : "(all campaigns)",
                rate14 * 100, baseline * 100, driftPct
        );
        log.warn("PIXEL_POLLUTION_ALERT tenant={} {}", tenantId, desc);

        // Circuit breaker L2 event for dashboard visibility
        try {
            circuitBreakerService.createPixelPollutionEvent(tenantId, campaign, desc);
        } catch (Exception e) {
            log.error("Failed to create CB event for pixel pollution: {}", e.getMessage());
        }

        // Alert (Telegram + Slack via AlertService)
        try {
            String alertMsg = String.format(
                    "🔴 Pixel Pollution Drift — Tenant: %s\n\nCampaign: %s\nQuality Rate (14d): %.1f%%\n" +
                    "Baseline (30d): %.1f%%\nDrift: %.1f%%\n\nAction Required: Review campaign targeting & creative.",
                    tenantId, campaign != null ? campaign : "(all)", rate14 * 100, baseline * 100, driftPct);
            alertService.sendAlert(alertMsg);
        } catch (Exception e) {
            log.error("Failed to send pixel pollution alert: {}", e.getMessage());
        }
    }

    // ─── Pure-logic helpers (package-private for unit tests) ────────────────

    static boolean shouldAlert(Double rate14d, Double baseline30d, int forms14d) {
        if (forms14d < MIN_FORM_VOLUME) return false;
        if (rate14d == null || baseline30d == null || baseline30d <= 0) return false;
        return rate14d < DRIFT_THRESHOLD * baseline30d;
    }

    static double driftPct(double rate14d, double baseline30d) {
        return (rate14d / baseline30d - 1.0) * 100.0;
    }

    // ─── Type helpers ────────────────────────────────────────────────────────

    private static int toInt(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static Double toDouble(Object v) {
        return v == null ? null : ((Number) v).doubleValue();
    }
}
