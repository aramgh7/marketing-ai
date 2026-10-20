package com.marketingai.scheduler;

import com.marketingai.service.MlServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FORM_NO_SLOT follow-up audience (roadmap 9-9; arch §16.5): a lead submitted the investor form but
 * booked no call within 24 h → assign a FORM_NO_SLOT follow-up variant through the same Thompson path
 * as BOOKED / NO_SHOW (ML /decide/followup). Hourly; idempotent per (session, audience).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormNoSlotScheduler {

    static final int MIN_AGE_HOURS = 24;
    static final int MAX_AGE_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 600_000)
    public void assign() {
        List<UUID> tenants = jdbc.queryForList("""
                SELECT DISTINCT tenant_id FROM followup_variants
                WHERE audience = 'FORM_NO_SLOT' AND (lifecycle_state = 'APPROVED' OR is_control = TRUE)
                """, UUID.class);
        for (UUID tenantId : tenants) {
            try {
                int n = assignTenant(tenantId);
                if (n > 0) log.info("FormNoSlot: tenant={} assigned {}", tenantId, n);
            } catch (Exception e) {
                log.warn("FormNoSlot: tenant {} skipped: {}", tenantId, e.getMessage());
            }
        }
    }

    int assignTenant(UUID tenantId) {
        List<Map<String, Object>> variants = jdbc.queryForList("""
                SELECT id, name, alpha, beta, is_control, lifecycle_state
                FROM followup_variants
                WHERE tenant_id = ? AND audience = 'FORM_NO_SLOT'
                  AND (lifecycle_state = 'APPROVED' OR is_control = TRUE)
                """, tenantId);
        if (variants.isEmpty()) return 0;

        List<Map<String, Object>> leads = jdbc.queryForList("""
                SELECT fs.session_id, fs.ticket_bucket, s.geo
                FROM form_submissions fs
                JOIN sessions s ON s.id = fs.session_id AND s.tenant_id = fs.tenant_id
                WHERE fs.tenant_id = ?
                  AND fs.submitted_at < now() - (? * interval '1 hour')
                  AND fs.submitted_at > now() - (? * interval '1 day')
                  AND NOT EXISTS (SELECT 1 FROM appointments a
                                  WHERE a.tenant_id = fs.tenant_id AND a.session_id = fs.session_id)
                  AND NOT EXISTS (SELECT 1 FROM followup_assignments fa
                                  WHERE fa.tenant_id = fs.tenant_id AND fa.session_id = fs.session_id
                                    AND fa.audience = 'FORM_NO_SLOT')
                LIMIT 200
                """, tenantId, MIN_AGE_HOURS, MAX_AGE_DAYS);

        int assigned = 0;
        for (Map<String, Object> lead : leads) {
            String sessionId = (String) lead.get("session_id");
            Map<String, Object> req = Map.of(
                    "tenant_id", tenantId.toString(),
                    "appointment_id", sessionId,                 // context key only — no appointment exists
                    "country_code", lead.get("geo") != null ? lead.get("geo") : "",
                    "ticket_bucket", lead.get("ticket_bucket") != null ? lead.get("ticket_bucket") : "",
                    "exploration_rate", 0.15,
                    "variants", variants);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mlClient.post("/decide/followup", req, Map.class);
            if (resp == null || resp.get("variant_id") == null) continue;
            jdbc.update("""
                    INSERT INTO followup_assignments
                        (id, tenant_id, appointment_id, session_id, variant_id, context_key, outcome, audience)
                    VALUES (?, ?, NULL, ?, ?::uuid, ?, 'UNKNOWN', 'FORM_NO_SLOT')
                    ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), tenantId, sessionId, resp.get("variant_id").toString(),
                    String.valueOf(resp.getOrDefault("context_key", "form_no_slot")));
            assigned++;
        }
        return assigned;
    }
}
