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
 * CRM Discipline weekly report (3-21).
 * Every Monday 08:00 UTC — per-tenant conversion and quality summary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrmDisciplineScheduler {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    @Scheduled(cron = "0 0 8 * * MON")
    public void sendWeeklyDisciplineReport() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id, name FROM tenants WHERE status = 'ACTIVE'");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            String tenantName = (String) tenant.getOrDefault("name", tenantId.toString());
            try {
                sendReportForTenant(tenantId, tenantName);
            } catch (Exception e) {
                log.warn("CrmDisciplineScheduler failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void sendReportForTenant(UUID tenantId, String tenantName) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    COUNT(*) AS total_sessions,
                    SUM(CASE WHEN converted THEN 1 ELSE 0 END) AS converted,
                    ROUND(AVG(quality_score)::numeric, 3) AS avg_quality_score,
                    SUM(CASE WHEN consent_level = 'full' THEN 1 ELSE 0 END) AS full_consent
                FROM sessions
                WHERE tenant_id = ?
                  AND created_at > now() - interval '7 days'
                """, tenantId);

        long total = toLong(row.get("total_sessions"));
        long converted = toLong(row.get("converted"));
        double convRate = total > 0 ? (double) converted / total : 0.0;
        double avgQs = row.get("avg_quality_score") != null
                ? ((Number) row.get("avg_quality_score")).doubleValue() : 0.0;
        long fullConsent = toLong(row.get("full_consent"));
        double consentRate = total > 0 ? (double) fullConsent / total : 0.0;

        String report = String.format(
                "📊 Weekly CRM Discipline Report — %s\n\n"
                + "Sessions (last 7d):    %,d\n"
                + "Conversions:           %,d (%.1f%%)\n"
                + "Avg Quality Score:     %.3f\n"
                + "Full-consent sessions: %,d (%.1f%%)\n\n"
                + "Review any consent_rate < 50%% for GDPR compliance.",
                tenantName, total, converted, convRate * 100,
                avgQs, fullConsent, consentRate * 100);

        alertService.sendAlert(report);
        log.info("CrmDiscipline report sent for tenant={}", tenantId);
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }
}
