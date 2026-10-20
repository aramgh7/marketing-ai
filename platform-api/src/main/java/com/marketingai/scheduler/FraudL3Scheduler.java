package com.marketingai.scheduler;

import com.marketingai.service.NightBurstMath;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fraud L3 detector — hourly CTR/conversion anomaly by geo×placement (3-20).
 * When fraud_rate > 30% for a segment, creates circuit_breaker_events level=3.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudL3Scheduler {

    private static final double FRAUD_L3_THRESHOLD = 0.30;

    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    @Scheduled(fixedDelay = 3_600_000)
    public void detectFraudL3() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            try {
                checkTenantFraudL3(tenantId);
            } catch (Exception e) {
                log.warn("FraudL3Scheduler failed for tenant={}: {}", tenantId, e.getMessage());
            }
            try {
                checkNightBurst(tenantId);
            } catch (Exception e) {
                log.warn("FraudL3 night-burst failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void checkTenantFraudL3(UUID tenantId) {
        List<Map<String, Object>> segments = jdbc.queryForList("""
                SELECT geo, COALESCE(utm_source, 'UNKNOWN') AS placement,
                       COUNT(*) AS total,
                       SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END) AS fraud_count
                FROM sessions
                WHERE tenant_id = ?
                  AND started_at > now() - interval '1 hour'
                GROUP BY geo, COALESCE(utm_source, 'UNKNOWN')
                HAVING COUNT(*) >= 20
                """, tenantId);

        int l3EventsCreated = 0;

        for (Map<String, Object> seg : segments) {
            long total = toLong(seg.get("total"));
            long fraudCount = toLong(seg.get("fraud_count"));
            double fraudRate = (double) fraudCount / total;

            if (fraudRate >= FRAUD_L3_THRESHOLD) {
                String geo = (String) seg.get("geo");
                String placement = (String) seg.get("placement");

                String reason = String.format(
                        "FRAUD_L3: geo=%s placement=%s fraud_rate=%.1f%% (%d/%d sessions)",
                        geo, placement, fraudRate * 100, fraudCount, total);

                // Check if same segment L3 already open
                List<Map<String, Object>> existing = jdbc.queryForList("""
                        SELECT id FROM circuit_breaker_events
                        WHERE tenant_id = ?
                          AND trigger_type = 'FRAUD_L3'
                          AND trigger_reason LIKE ?
                          AND resolved_at IS NULL
                        """, tenantId, "%" + geo + "%" + placement + "%");

                if (existing.isEmpty()) {
                    jdbc.update("""
                            INSERT INTO circuit_breaker_events
                            (tenant_id, level, trigger_type, trigger_reason, metric, value, action_taken, created_at)
                            VALUES (?, 'L3', 'FRAUD_L3', ?, 'fraud_rate', ?, 'PAUSE_SEGMENT', now())
                            """, tenantId, reason, (float) fraudRate);

                    l3EventsCreated++;
                    log.warn("FraudL3: tenant={} geo={} placement={} rate={}%",
                            tenantId, geo, placement, String.format("%.1f", fraudRate * 100));
                }
            }
        }

        if (l3EventsCreated > 0) {
            alertService.sendAlert(String.format(
                    "🔴 Fraud L3 Alert — Tenant: %s\n%d segment(s) exceeded 30%% fraud rate in the last hour.\n"
                    + "Check circuit_breaker_events and review Meta placements.",
                    tenantId, l3EventsCreated));
        }
    }

    /**
     * Roadmap 3-20: night-hour click bursts (23:00–05:00 in tenants.settings.timezone).
     * 10+ clicks within 15 minutes at night → circuit_breaker_events L3 FRAUD_NIGHT_BURST + alert
     * (one open event per tenant). Thresholds: settings.night_burst_threshold / night_burst_window_min.
     */
    private void checkNightBurst(UUID tenantId) {
        Map<String, Object> settings = com.marketingai.util.Jsonb.asMap(
                jdbc.queryForObject("SELECT settings FROM tenants WHERE id = ?", Object.class, tenantId));
        java.time.ZoneId zone = NightBurstMath.zoneOf(settings.get("timezone"));
        int threshold = settings.get("night_burst_threshold") instanceof Number n ? n.intValue() : NightBurstMath.DEFAULT_THRESHOLD;
        int windowMin = settings.get("night_burst_window_min") instanceof Number w ? w.intValue() : NightBurstMath.DEFAULT_WINDOW_MINUTES;

        List<java.time.Instant> clicks = jdbc.query("""
                SELECT event_time FROM events
                WHERE tenant_id = ? AND event_type IN ('CLICK', 'PAGE_VIEW')
                  AND event_time > now() - INTERVAL '24 hours'
                """, (rs, i) -> rs.getTimestamp("event_time").toInstant(), tenantId);
        NightBurstMath.Result r = NightBurstMath.detect(clicks, zone, threshold, windowMin);
        if (!r.burst()) return;

        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id FROM circuit_breaker_events
                WHERE tenant_id = ? AND trigger_type = 'FRAUD_NIGHT_BURST' AND resolved_at IS NULL
                """, tenantId);
        if (!existing.isEmpty()) return;
        String reason = String.format(java.util.Locale.ROOT,
                "FRAUD_NIGHT_BURST: %d clicks within %d min between 23:00 and 05:00 %s (%d night clicks in 24h)",
                r.maxInWindow(), r.windowMinutes(), zone.getId(), r.nightEvents());
        jdbc.update("""
                INSERT INTO circuit_breaker_events
                (tenant_id, level, trigger_type, trigger_reason, metric, value, action_taken, created_at)
                VALUES (?, 'L3', 'FRAUD_NIGHT_BURST', ?, 'night_clicks_15min', ?, 'REVIEW_PLACEMENTS', now())
                """, tenantId, reason, (float) r.maxInWindow());
        alertService.sendAlert("🔴 Fraud L3 — Tenant " + tenantId + ": " + reason
                + "\nBot-like traffic pattern; review placements and IP clusters.");
        log.warn("FraudL3 night burst: tenant={} {}", tenantId, reason);
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }
}
