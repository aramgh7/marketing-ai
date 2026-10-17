package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Investor Archetypes (vision §19.18.6; arch §16.19).
 * Classifies leads into archetypes; upserts result; drives path/follow-up/brief routing.
 */
@RestController
@RequestMapping("/api/v1/investor-archetypes")
@RequiredArgsConstructor
@Slf4j
public class InvestorArchetypeController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/classify")
    public ResponseEntity<Map<String, Object>> classify(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        UUID sessionId = body.get("session_id") != null
            ? UUID.fromString((String) body.get("session_id")) : null;

        Map<String, Object> features = buildFeatures(tenantId, sessionId, body);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                "http://ml-service:8001/investor/classify", features, Map.class);
            if (result == null) return ResponseEntity.ok(Map.of("archetype", "UNKNOWN"));

            jdbc.update("""
                INSERT INTO investor_archetypes
                    (tenant_id, session_id, archetype, confidence, signals)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, session_id) DO UPDATE SET
                    archetype     = EXCLUDED.archetype,
                    confidence    = EXCLUDED.confidence,
                    signals       = EXCLUDED.signals,
                    classified_at = NOW()
                """, tenantId, sessionId,
                result.get("archetype"),
                result.get("confidence"),
                toJson(result.get("signals")));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("Archetype classification failed for session {}: {}", sessionId, e.getMessage());
            return ResponseEntity.ok(Map.of("archetype", "UNKNOWN", "error", e.getMessage()));
        }
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> getBySession(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM investor_archetypes WHERE session_id = ? AND tenant_id = ?
            """, sessionId, tenantId);
        return rows.isEmpty() ? ResponseEntity.notFound().build()
                              : ResponseEntity.ok(rows.get(0));
    }

    @GetMapping("/distribution")
    public ResponseEntity<List<Map<String, Object>>> distribution(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT archetype, COUNT(*) AS count, AVG(confidence) AS avg_confidence
            FROM investor_archetypes
            WHERE tenant_id = ? AND classified_at >= NOW() - (? || ' days')::interval
            GROUP BY archetype ORDER BY count DESC
            """, tenantId, String.valueOf(days)));
    }

    @GetMapping("/policies")
    public ResponseEntity<List<Map<String, Object>>> policies(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT * FROM archetype_policies WHERE tenant_id = ? AND is_active = TRUE
            ORDER BY cluster_label
            """, tenantId));
    }

    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> upsertPolicy(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO archetype_policies
                (id, tenant_id, cluster_label, funnel_path_key, first_touch_format,
                 followup_audience_override, brief_segment)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, cluster_label) DO UPDATE SET
                funnel_path_key            = EXCLUDED.funnel_path_key,
                first_touch_format         = EXCLUDED.first_touch_format,
                followup_audience_override = EXCLUDED.followup_audience_override,
                brief_segment              = EXCLUDED.brief_segment
            """, id, tenantId,
            body.get("cluster_label"), body.get("funnel_path_key"),
            body.get("first_touch_format"), body.get("followup_audience_override"),
            body.get("brief_segment"));
        return ResponseEntity.ok(Map.of("id", id));
    }

    private Map<String, Object> buildFeatures(UUID tenantId, UUID sessionId, Map<String, Object> body) {
        Map<String, Object> features = new HashMap<>(body);
        if (sessionId != null) {
            try {
                List<Map<String, Object>> sessions = jdbc.queryForList("""
                    SELECT ticket_bucket, self_accredited, device_type,
                        EXTRACT(EPOCH FROM (form_submitted_at - created_at))/3600 AS time_to_form_h
                    FROM sessions WHERE id = ? AND tenant_id = ?
                    """, sessionId, tenantId);
                if (!sessions.isEmpty()) sessions.get(0).forEach(features::putIfAbsent);

                List<Map<String, Object>> eventCounts = jdbc.queryForList("""
                    SELECT event_type, COUNT(*) AS cnt
                    FROM events WHERE session_id = ? AND tenant_id = ? GROUP BY event_type
                    """, sessionId, tenantId);
                features.putIfAbsent("material_views", eventCounts.stream()
                    .filter(r -> "MATERIAL_VIEW".equals(r.get("event_type")))
                    .mapToInt(r -> ((Number) r.get("cnt")).intValue()).sum());
                features.putIfAbsent("dataroom_opens", eventCounts.stream()
                    .filter(r -> "DATAROOM_OPEN".equals(r.get("event_type")))
                    .mapToInt(r -> ((Number) r.get("cnt")).intValue()).sum());

                List<Map<String, Object>> callNotes = jdbc.queryForList("""
                    SELECT call_outcome, objection_tags FROM call_notes
                    WHERE session_id = ? AND tenant_id = ? ORDER BY created_at DESC LIMIT 1
                    """, sessionId, tenantId);
                if (!callNotes.isEmpty()) {
                    features.putIfAbsent("call_outcome",   callNotes.get(0).get("call_outcome"));
                    features.putIfAbsent("objection_tags", callNotes.get(0).get("objection_tags"));
                }

                List<Map<String, Object>> noshow = jdbc.queryForList("""
                    SELECT risk_tier FROM noshow_scores
                    WHERE session_id = ? AND tenant_id = ? ORDER BY scored_at DESC LIMIT 1
                    """, sessionId, tenantId);
                if (!noshow.isEmpty()) features.putIfAbsent("noshow_risk_tier", noshow.get(0).get("risk_tier"));

                List<Map<String, Object>> touchCount = jdbc.queryForList("""
                    SELECT COUNT(*) AS cnt FROM touchpoints WHERE session_id = ? AND tenant_id = ?
                    """, sessionId, tenantId);
                if (!touchCount.isEmpty())
                    features.putIfAbsent("touch_count", ((Number) touchCount.get(0).get("cnt")).intValue());

            } catch (Exception e) {
                log.warn("Could not load features for session {}: {}", sessionId, e.getMessage());
            }
        }
        features.remove("session_id");
        return features;
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }
}
