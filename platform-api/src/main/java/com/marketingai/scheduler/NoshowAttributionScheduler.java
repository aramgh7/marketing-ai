package com.marketingai.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.service.MlServiceClient;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly no-show attribution fit (roadmap 8-3; vision §19.5; arch §16.6).
 *
 * Every resolved appointment (COMPLETED / NO_SHOW) is joined to the session's creative atoms,
 * booking distance, timezone knowledge and ticket bucket, then posted to
 * {@code POST /analyze/noshow-attribution}. The coefficients are stored in
 * {@code noshow_attribution_runs}; no-show drivers feed the creator brief (BriefService) and
 * the clickbait detector.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NoshowAttributionScheduler {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Scheduled(cron = "0 30 5 * * MON")
    public void weeklyFit() {
        List<UUID> tenants = jdbc.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                fitTenant(tenantId);
            } catch (Exception e) {
                log.warn("NoshowAttribution failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fitTenant(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id AS appointment_id, a.status, a.booking_distance_h,
                       (a.lead_tz IS NOT NULL) AS tz_known,
                       s.ticket_bucket AS segment,
                       (SELECT COALESCE(jsonb_object_agg(ca.atom_type, ca.atom_value), '{}'::jsonb)
                        FROM creative_atoms ca
                        WHERE ca.creative_id = s.creative_id AND ca.tenant_id = s.tenant_id) AS atoms
                FROM appointments a
                JOIN sessions s ON s.id = a.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = ?
                  AND a.status IN ('COMPLETED', 'NO_SHOW')
                  AND a.scheduled_at > now() - INTERVAL '180 days'
                  AND s.creative_id IS NOT NULL
                """, tenantId);

        List<Map<String, Object>> samples = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new HashMap<>();
            m.put("atoms", Jsonb.asMap(r.get("atoms")));
            m.put("booking_distance_h", r.get("booking_distance_h"));
            m.put("tz_known", Boolean.TRUE.equals(r.get("tz_known")));
            m.put("segment", r.get("segment"));
            m.put("showed", "COMPLETED".equals(r.get("status")));
            samples.add(m);
        }

        Map<String, Object> result = mlClient.post("/analyze/noshow-attribution",
                Map.of("tenant_id", tenantId.toString(), "rows", samples), Map.class);
        if (result == null) result = Map.of("fitted", false, "reason", "ml service returned null");

        jdbc.update("""
                INSERT INTO noshow_attribution_runs
                    (tenant_id, n_bookings, show_rate, fitted, model_version, coefficients, drivers, reason)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """,
                tenantId, samples.size(),
                result.get("show_rate") instanceof Number n ? n.floatValue() : null,
                Boolean.TRUE.equals(result.get("fitted")),
                result.get("model_version"),
                toJson(result.getOrDefault("coefficients", List.of())),
                toJson(result.getOrDefault("drivers", List.of())),
                result.get("reason") != null ? String.valueOf(result.get("reason")) : null);

        log.info("NoshowAttribution tenant={} n={} fitted={} drivers={}", tenantId, samples.size(),
                result.get("fitted"), ((List<?>) result.getOrDefault("drivers", List.of())).size());
        return result;
    }

    private static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }
}
