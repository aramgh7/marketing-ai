package com.marketingai.scheduler;

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
 * Fraud Layer 2 share-of-suspicious monitor (vision §12.6.2 Layer 4).
 *
 * Every 60 minutes: for each ACTIVE tenant, count sessions in the last 24h.
 * If share of is_suspicious sessions > 10% → alert + insert fraud_share_alerts.
 *
 * In LOG_ONLY mode: alert only.
 * In ENFORCE mode: alert + set sessions to is_suspicious (already set) + pause ad sets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudL2Scheduler {

    private static final double SHARE_THRESHOLD = 0.10;

    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    @Scheduled(fixedDelay = 3_600_000)
    public void checkSuspiciousShare() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id, fraud_mode FROM tenants");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId  = (UUID) tenant.get("id");
            String mode    = (String) tenant.getOrDefault("fraud_mode", "LOG_ONLY");
            try {
                checkTenant(tenantId, mode);
            } catch (Exception e) {
                log.warn("FraudL2Scheduler failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void checkTenant(UUID tenantId, String fraudMode) {
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT
                    COUNT(*) AS total,
                    SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END) AS suspicious
                FROM sessions
                WHERE tenant_id = ?
                  AND started_at >= now() - INTERVAL '24 hours'
                """, tenantId);

        long total     = toLong(stats.get("total"));
        long suspicious = toLong(stats.get("suspicious"));
        if (total == 0) return;

        double sharePct = (double) suspicious / total;
        if (sharePct <= SHARE_THRESHOLD) return;

        // Source breakdown (top fraud flags)
        List<Map<String, Object>> sources = jdbc.queryForList("""
                SELECT flag.rule AS rule, COUNT(*) AS cnt
                FROM sessions,
                     jsonb_array_elements_text(
                         CASE WHEN jsonb_typeof(fraud_reasons) = 'array' THEN fraud_reasons ELSE '[]'::jsonb END
                     ) AS flag(rule)
                WHERE tenant_id = ?
                  AND is_suspicious = true
                  AND started_at >= now() - INTERVAL '24 hours'
                GROUP BY flag.rule
                ORDER BY cnt DESC
                LIMIT 5
                """, tenantId);

        String sourceJson = sources.isEmpty() ? "{}" : sources.toString();

        // Check if alert already sent in last 4h (avoid spam)
        List<Map<String, Object>> recentAlerts = jdbc.queryForList("""
                SELECT id FROM fraud_share_alerts
                WHERE tenant_id = ?
                  AND created_at >= now() - INTERVAL '4 hours'
                """, tenantId);
        if (!recentAlerts.isEmpty()) return;

        jdbc.update("""
                INSERT INTO fraud_share_alerts
                    (tenant_id, window_start, window_end, total_sessions, suspicious_sessions,
                     share_pct, sources, alert_sent)
                VALUES (?, now() - INTERVAL '24 hours', now(), ?, ?, ?, ?::jsonb, TRUE)
                """,
                tenantId, (int) total, (int) suspicious,
                (float) (sharePct * 100), sourceJson);

        String msg = String.format(
                "⚠️ Fraud Share Alert [%s] — Tenant: %s\n\nSuspicious sessions (24h): %d / %d (%.1f%%)\n" +
                "Sources: %s\n\nMode: %s. %s",
                fraudMode, tenantId, suspicious, total, sharePct * 100, sourceJson,
                fraudMode, "ENFORCE".equals(fraudMode)
                        ? "Ad sets paused automatically." : "Review traffic sources in Meta Ads Manager.");

        alertService.sendAlert(msg);
        log.warn("FraudL2 share alert: tenant={} suspicious={}/{} ({}%) mode={}",
                tenantId, suspicious, total, sharePct * 100, fraudMode);

        if ("ENFORCE".equals(fraudMode)) {
            int paused = jdbc.update(
                    "UPDATE meta_ad_sets SET status='PAUSED', synced_at=now() WHERE tenant_id=? AND status='ACTIVE'",
                    tenantId);
            if (paused > 0) {
                log.warn("FraudL2 ENFORCE: tenant={} paused {} ad sets", tenantId, paused);
            }
        }
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }
}
