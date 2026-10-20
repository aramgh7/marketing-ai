package com.marketingai.controller;

import com.marketingai.util.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Arch §15.5 / roadmap 9-5: read side of the Quality Score.
 *   GET /api/v1/quality-score?by=creative|variant&days=30
 * Rows come from creative_quality (latest aggregate per entity, computed by QualityScoreScheduler)
 * joined with the session counts of the window; show_rate from appointments of the entity's sessions.
 */
@RestController
@RequestMapping("/quality-score")
@RequiredArgsConstructor
public class QualityScoreController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SQL_BY_CREATIVE = """
            SELECT q.creative_id AS id,
                   (SELECT title FROM creatives c WHERE c.id = q.creative_id) AS name,
                   q.quality_score, q.clickbait_suspect, q.components, q.computed_at::text AS computed_at,
                   (SELECT COUNT(*) FROM sessions s
                     WHERE s.tenant_id = q.tenant_id AND s.creative_id = q.creative_id
                       AND s.started_at >= now() - (? * interval '1 day'))                      AS n_sessions,
                   (SELECT AVG(CASE WHEN a.status = 'COMPLETED' THEN 1.0 WHEN a.status = 'NO_SHOW' THEN 0.0 END)
                      FROM appointments a JOIN sessions s2 ON s2.id = a.session_id
                     WHERE a.tenant_id = q.tenant_id AND s2.creative_id = q.creative_id
                       AND a.scheduled_at >= now() - (? * interval '1 day'))                    AS show_rate
            FROM creative_quality q
            WHERE q.tenant_id = ? AND q.creative_id IS NOT NULL
              AND q.computed_at = (SELECT MAX(q2.computed_at) FROM creative_quality q2
                                   WHERE q2.tenant_id = q.tenant_id AND q2.creative_id = q.creative_id)
            ORDER BY q.quality_score DESC NULLS LAST
            LIMIT 200
            """;

    private static final String SQL_BY_VARIANT = """
            SELECT q.variant_id AS id,
                   (SELECT name FROM landing_variants v WHERE v.id = q.variant_id) AS name,
                   q.quality_score, q.clickbait_suspect, q.components, q.computed_at::text AS computed_at,
                   (SELECT COUNT(*) FROM sessions s
                     WHERE s.tenant_id = q.tenant_id AND s.variant_id = q.variant_id
                       AND s.started_at >= now() - (? * interval '1 day'))                      AS n_sessions,
                   (SELECT AVG(CASE WHEN a.status = 'COMPLETED' THEN 1.0 WHEN a.status = 'NO_SHOW' THEN 0.0 END)
                      FROM appointments a JOIN sessions s2 ON s2.id = a.session_id
                     WHERE a.tenant_id = q.tenant_id AND s2.variant_id = q.variant_id
                       AND a.scheduled_at >= now() - (? * interval '1 day'))                    AS show_rate
            FROM creative_quality q
            WHERE q.tenant_id = ? AND q.variant_id IS NOT NULL
              AND q.computed_at = (SELECT MAX(q2.computed_at) FROM creative_quality q2
                                   WHERE q2.tenant_id = q.tenant_id AND q2.variant_id = q.variant_id)
            ORDER BY q.quality_score DESC NULLS LAST
            LIMIT 200
            """;

    private final JdbcTemplate jdbc;

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute("tenantId") UUID tenantId,
                                          @RequestParam(defaultValue = "creative") String by,
                                          @RequestParam(defaultValue = "30") int days) {
        boolean byVariant = "variant".equalsIgnoreCase(by);
        int windowDays = Math.max(1, Math.min(days, 365));
        List<Map<String, Object>> rows = jdbc.queryForList(byVariant ? SQL_BY_VARIANT : SQL_BY_CREATIVE,
                windowDays, windowDays, tenantId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            JsonNode c = components(r.get("components"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(r.get("id")));
            m.put("name", r.get("name"));
            m.put("quality_score", r.get("quality_score"));
            m.put("n_sessions", r.get("n_sessions"));
            m.put("bounce_rate", num(c, "bounce_rate"));
            m.put("form_completion", num(c, "form_completion_rate"));
            m.put("match_score", num(c, "match_score"));
            m.put("show_rate", r.get("show_rate"));
            m.put("clickbait_flag", Boolean.TRUE.equals(r.get("clickbait_suspect")));
            m.put("computed_at", r.get("computed_at"));
            out.add(m);
        }
        return out;
    }

    private static JsonNode components(Object raw) {
        try {
            String json = Jsonb.asString(raw);
            return json == null ? MAPPER.createObjectNode() : MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static Double num(JsonNode c, String key) {
        JsonNode n = c.path(key);
        return n.isNumber() ? n.asDouble() : null;
    }
}
