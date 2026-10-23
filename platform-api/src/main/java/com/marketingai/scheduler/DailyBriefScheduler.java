package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import com.marketingai.dto.analytics.OverviewResponse;
import com.marketingai.security.TenantContext;
import com.marketingai.service.AlertService;
import com.marketingai.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily performance brief sent to every active tenant at 07:00 UTC.
 *
 * <p>D-23: deduplication by (tenant_id, date) — if the scheduler restarts or runs twice
 * on the same day, a UNIQUE constraint on {@code daily_brief_log(tenant_id, brief_date)}
 * prevents double-delivery.  The {@code ON CONFLICT DO NOTHING} insert is the guard; we
 * only send when the insert succeeds (i.e. the row didn't exist yet).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyBriefScheduler {

    private final JdbcTemplate jdbc;
    private final AnalyticsService analyticsService;
    private final AlertService alertService;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    public void sendDailyBriefs() {
        if (!appProperties.getReport().isEnabled()) {
            log.info("DailyBriefScheduler: reports disabled — skipping");
            return;
        }

        LocalDate today = LocalDate.now();

        // D-7 pattern: findAll in system scope
        List<Map<String, Object>> tenants =
                TenantContext.callAsSystem(() -> jdbc.queryForList("SELECT id, name FROM tenants"));

        for (Map<String, Object> row : tenants) {
            UUID   tenantId   = (UUID)   row.get("id");
            String tenantName = (String) row.get("name");
            try {
                // D-23: attempt dedup insert — if it returns 0, brief already sent today
                int inserted = jdbc.update("""
                        INSERT INTO daily_brief_log (tenant_id, brief_date, channel)
                        VALUES (?, ?, 'ALERT')
                        ON CONFLICT (tenant_id, brief_date) DO NOTHING
                        """, tenantId, today);

                if (inserted == 0) {
                    log.debug("DailyBriefScheduler: already sent for tenant {} on {}", tenantId, today);
                    continue;
                }

                // Send within tenant scope so RLS is enforced
                TenantContext.runAs(tenantId.toString(), () -> sendBrief(tenantId, tenantName));
            } catch (Exception e) {
                log.error("DailyBriefScheduler: failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void sendBrief(UUID tenantId, String tenantName) {
        try {
            OverviewResponse ov = analyticsService.overview(1);

            String brief = String.format(
                    "📊 Daily Brief — %s (%s)\n" +
                    "Sessions:    %,d\n" +
                    "Conversions: %,d\n" +
                    "CVR:         %s\n" +
                    "Revenue:     $%.2f\n",
                    tenantName, LocalDate.now(),
                    ov.getTotalSessions(),
                    ov.getTotalConversions(),
                    ov.getConversionRate() != null
                            ? String.format("%.2f%%", ov.getConversionRate() * 100) : "n/a",
                    ov.getTotalRevenue());

            alertService.sendAlert(brief);
            log.info("DailyBriefScheduler: brief sent for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("DailyBriefScheduler: brief send failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
