package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes tenant-specific diagnostic baselines per placement × device (vision §3.2, arch §15.21).
 * Runs weekly Sunday 05:00 UTC. Upserts into diagnostic_baselines.
 * Uses first-party event data only (sessions + events tables).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosticsBaselineScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 5 * * SUN", zone = "UTC")
    public void computeBaselines() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants WHERE status = 'ACTIVE'");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            try {
                computeForTenant(tenantId);
            } catch (Exception e) {
                log.error("DiagnosticsBaselineScheduler failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void computeForTenant(UUID tenantId) {
        // Bounce rate per placement × device for last 90 days
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    COALESCE(s.utm_source, 'UNKNOWN')   AS placement,
                    CASE
                        WHEN s.device_type IN ('MOBILE','DESKTOP','TABLET') THEN s.device_type
                        ELSE 'UNKNOWN'
                    END                                  AS device_type,
                    COUNT(*)                             AS n,
                    AVG(CASE WHEN s.bounce THEN 1.0 ELSE 0.0 END) AS bounce_mean,
                    STDDEV_POP(CASE WHEN s.bounce THEN 1.0 ELSE 0.0 END) AS bounce_std,
                    AVG(s.quality_score)                 AS quality_mean,
                    STDDEV_POP(s.quality_score)          AS quality_std
                FROM sessions s
                WHERE s.tenant_id = ?
                  AND s.created_at >= NOW() - INTERVAL '90 days'
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                GROUP BY placement, device_type
                HAVING COUNT(*) >= 30
                """, tenantId);

        for (Map<String, Object> row : rows) {
            String placement  = (String) row.get("placement");
            String deviceType = (String) row.get("device_type");
            int    n          = ((Number) row.get("n")).intValue();
            double bounceMean = row.get("bounce_mean") != null ? ((Number) row.get("bounce_mean")).doubleValue() : 0.0;
            Double bounceStd  = row.get("bounce_std")  != null ? ((Number) row.get("bounce_std")).doubleValue()  : null;

            upsertBaseline(tenantId, placement, deviceType, "BOUNCE_RATE", bounceMean,
                    bounceStd != null ? bounceStd : bounceMean * 0.2 + 0.01, n);
        }

        log.debug("DiagnosticsBaseline: tenant {} — {} rows upserted", tenantId, rows.size());
    }

    private void upsertBaseline(UUID tenantId, String placement, String device, String metric,
                                double value, double std, int n) {
        jdbc.update("""
                INSERT INTO diagnostic_baselines
                    (id, tenant_id, placement, device_type, metric, baseline_value, sample_size,
                     confidence_lo, confidence_hi, computed_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                ON CONFLICT (tenant_id, placement, device_type, metric)
                DO UPDATE SET
                    baseline_value = EXCLUDED.baseline_value,
                    sample_size    = EXCLUDED.sample_size,
                    confidence_lo  = EXCLUDED.confidence_lo,
                    confidence_hi  = EXCLUDED.confidence_hi,
                    computed_at    = NOW()
                """,
                tenantId, placement, device, metric, value, n,
                value - 1.96 * std / Math.sqrt(n),
                value + 1.96 * std / Math.sqrt(n));
    }
}
