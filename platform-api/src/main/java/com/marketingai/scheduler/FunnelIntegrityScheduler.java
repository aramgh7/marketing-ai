package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.FunnelIntegrityMath;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily funnel integrity check (roadmap 8-9; vision §19.12): BOOKED (GHL appointments) vs
 * Schedule (Meta CAPI events actually SENT) vs FORM_SUBMIT (first-party form submissions) over
 * the last 24 h. A relative delta above the threshold (tenants.settings.funnel_integrity_delta,
 * default 10 %) raises an alert — one of the three pipes is leaking.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FunnelIntegrityScheduler {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    @Scheduled(cron = "0 15 7 * * *")
    public void dailyCheck() {
        List<Map<String, Object>> tenants = jdbc.queryForList("SELECT id, name, settings FROM tenants");
        for (Map<String, Object> t : tenants) {
            UUID tenantId = (UUID) t.get("id");
            try {
                FunnelIntegrityMath.Result r = checkTenant(tenantId, Jsonb.asMap(t.get("settings")));
                if (r.alert()) {
                    alertService.sendAlert("⚠️ " + t.get("name") + " — " + FunnelIntegrityMath.describe(r)
                            + "\nCheck: GHL webhook secret per sub-account, CAPI token/pixel role, funnel stage mapping.");
                }
            } catch (Exception e) {
                log.warn("FunnelIntegrity failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    public FunnelIntegrityMath.Result checkTenant(UUID tenantId, Map<String, Object> settings) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    (SELECT COUNT(*) FROM form_submissions fs
                      WHERE fs.tenant_id = ? AND fs.submitted_at >= now() - INTERVAL '24 hours')         AS form_submits,
                    (SELECT COUNT(*) FROM appointments a
                      WHERE a.tenant_id = ? AND a.created_at >= now() - INTERVAL '24 hours')             AS booked,
                    (SELECT COUNT(*) FROM capi_events c
                      WHERE c.tenant_id = ? AND c.event_name = 'Schedule' AND c.status = 'SENT'
                        AND c.created_at >= now() - INTERVAL '24 hours')                                  AS schedule_sent
                """, tenantId, tenantId, tenantId);

        Double threshold = null;
        Object t = settings == null ? null : settings.get("funnel_integrity_delta");
        if (t != null) {
            try { threshold = Double.parseDouble(t.toString()); } catch (NumberFormatException ignored) { /* default */ }
        }

        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(
                ((Number) row.get("form_submits")).intValue(),
                ((Number) row.get("booked")).intValue(),
                ((Number) row.get("schedule_sent")).intValue(),
                threshold);

        jdbc.update("""
                INSERT INTO funnel_integrity_checks
                    (tenant_id, check_date, window_hours, form_submits, booked, schedule_sent,
                     delta_booked_schedule, delta_form_booked, verdict, alert_fired)
                VALUES (?, CURRENT_DATE, 24, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, check_date) DO UPDATE SET
                    form_submits = EXCLUDED.form_submits, booked = EXCLUDED.booked,
                    schedule_sent = EXCLUDED.schedule_sent,
                    delta_booked_schedule = EXCLUDED.delta_booked_schedule,
                    delta_form_booked = EXCLUDED.delta_form_booked,
                    verdict = EXCLUDED.verdict, alert_fired = EXCLUDED.alert_fired
                """, tenantId, r.formSubmits(), r.booked(), r.scheduleSent(),
                r.deltaBookedSchedule() == null ? null : r.deltaBookedSchedule().floatValue(),
                r.deltaFormBooked() == null ? null : r.deltaFormBooked().floatValue(),
                r.verdict(), r.alert());

        log.info("FunnelIntegrity tenant={} {}", tenantId, FunnelIntegrityMath.describe(r));
        return r;
    }
}
