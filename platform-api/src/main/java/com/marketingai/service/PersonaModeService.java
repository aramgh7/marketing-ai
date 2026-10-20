package com.marketingai.service;

import com.marketingai.util.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persona-sandbox protocol (roadmap 9-9; arch §15.9).
 *   assignArm(tenantId, batchId)  – called by the batch launcher right after the ad sets of a batch are
 *                                   created (wiring point: TwoContourService.launchTestBatch);
 *                                   AB_TEST alternates PREFILTER / CONTROL per batch.
 *   weeklyCompare(tenantId)       – PersonaPromotionScheduler: gate pass rate per arm → PREFILTER when
 *                                   PersonaModeMath says so.
 * Mode lives in tenants.settings.persona_mode; the AB start date in tenants.settings.persona_ab_started_at.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaModeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    public String currentMode(UUID tenantId) {
        JsonNode settings = settings(tenantId);
        boolean hasPersona = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM personas WHERE tenant_id = ?)", Boolean.class, tenantId));
        return PersonaModeMath.resolveMode(settings.path("persona_mode").asText(null), hasPersona);
    }

    /** Stamp the persona arm on every ad set of the batch; returns the arm (null when personas are off). */
    public String assignArm(UUID tenantId, UUID batchId) {
        String mode = currentMode(tenantId);
        Long batches = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT batch_id) FROM meta_ad_sets
                WHERE tenant_id = ? AND persona_arm IS NOT NULL AND batch_id <> ?
                """, Long.class, tenantId, batchId);
        String arm = PersonaModeMath.arm(mode, batches == null ? 0 : batches);
        if (arm == null) return null;
        jdbc.update("UPDATE meta_ad_sets SET persona_arm = ? WHERE tenant_id = ? AND batch_id = ?", arm, tenantId, batchId);
        if ("AB_TEST".equals(mode)) {
            jdbc.update("""
                    UPDATE tenants SET settings = COALESCE(settings, '{}'::jsonb)
                        || jsonb_build_object('persona_ab_started_at', COALESCE(settings->>'persona_ab_started_at', now()::text))
                    WHERE id = ?
                    """, tenantId);
        }
        log.info("PersonaMode: tenant={} batch={} mode={} arm={}", tenantId, batchId, mode, arm);
        return arm;
    }

    /** Weekly: compare gate pass rates per arm; promote to PREFILTER when the rule holds. */
    public Map<String, Object> weeklyCompare(UUID tenantId) {
        String mode = currentMode(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        if (!"AB_TEST".equals(mode)) {
            out.put("promoted", false);
            out.put("reason", "not in AB_TEST");
            return out;
        }
        Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(DISTINCT batch_id) FILTER (WHERE persona_arm = 'PREFILTER')                               AS pre_total,
                       COUNT(DISTINCT batch_id) FILTER (WHERE persona_arm = 'PREFILTER' AND gate_status = 'PASSED')   AS pre_passed,
                       COUNT(DISTINCT batch_id) FILTER (WHERE persona_arm = 'CONTROL')                                 AS ctl_total,
                       COUNT(DISTINCT batch_id) FILTER (WHERE persona_arm = 'CONTROL'   AND gate_status = 'PASSED')   AS ctl_passed
                FROM meta_ad_sets
                WHERE tenant_id = ? AND batch_id IS NOT NULL
                  AND batch_launched_at < now() - interval '14 days'
                """, tenantId);
        JsonNode settings = settings(tenantId);
        long abDays = 0;
        String started = settings.path("persona_ab_started_at").asText(null);
        if (started != null) {
            try {
                abDays = ChronoUnit.DAYS.between(Timestamp.valueOf(started.replace("T", " ").split("\\+")[0]
                        .replaceAll("Z$", "")).toInstant(), Instant.now());
            } catch (Exception e) {
                abDays = 0;
            }
        }
        PersonaModeMath.Decision d = PersonaModeMath.evaluate(
                n(stats.get("pre_passed")), n(stats.get("pre_total")),
                n(stats.get("ctl_passed")), n(stats.get("ctl_total")), abDays);
        out.put("prefilter", Map.of("passed", n(stats.get("pre_passed")), "total", n(stats.get("pre_total"))));
        out.put("control", Map.of("passed", n(stats.get("ctl_passed")), "total", n(stats.get("ctl_total"))));
        out.put("ab_days", abDays);
        out.put("p_better", d.pBetter());
        out.put("promoted", d.promote());
        out.put("reason", d.reason());
        if (d.promote()) {
            jdbc.update("""
                    UPDATE tenants SET settings = COALESCE(settings, '{}'::jsonb)
                        || jsonb_build_object('persona_mode', 'PREFILTER', 'persona_promoted_at', now()::text)
                    WHERE id = ?
                    """, tenantId);
            alertService.sendAlert("🎭 Persona sandbox promoted to PREFILTER for tenant " + tenantId + ": " + d.reason());
        }
        return out;
    }

    private JsonNode settings(UUID tenantId) {
        try {
            Object raw = jdbc.queryForObject("SELECT settings FROM tenants WHERE id = ?", Object.class, tenantId);
            String json = Jsonb.asString(raw);
            return json == null ? MAPPER.createObjectNode() : MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static int n(Object o) {
        return o instanceof Number num ? num.intValue() : 0;
    }
}
