package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Post-Call Intelligence (vision §19.18.5; arch §16.18).
 * Coordinator submits structured call outcomes; objection tags route follow-up arms.
 */
@RestController
@RequestMapping("/post-call")
@RequiredArgsConstructor
@Slf4j
public class PostCallController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/notes")
    public ResponseEntity<Map<String, Object>> submitNote(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        UUID id      = UUID.randomUUID();
        String outcome  = (String) body.get("call_outcome");
        String nextStep = (String) body.getOrDefault("next_step", "NONE");
        Object tagsRaw  = body.getOrDefault("objection_tags", List.of());

        jdbc.update("""
            INSERT INTO call_notes
                (id, tenant_id, session_id, appointment_id,
                 call_outcome, next_step, objection_tags, coordinator_note, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, id, tenantId,
            body.get("session_id")     != null ? UUID.fromString((String) body.get("session_id"))     : null,
            body.get("appointment_id") != null ? UUID.fromString((String) body.get("appointment_id")) : null,
            outcome, nextStep, toJson(tagsRaw),
            body.get("coordinator_note"),
            body.getOrDefault("created_by", "coordinator"));

        // Expand objection_tags into call_objections for per-creative linking
        if (tagsRaw instanceof List<?> tags && !tags.isEmpty()) {
            UUID sessionId = body.get("session_id") != null
                ? UUID.fromString((String) body.get("session_id")) : null;
            for (Object tag : tags) {
                if (tag == null) continue;
                jdbc.update("""
                    INSERT INTO call_objections
                        (tenant_id, call_note_id, objection_tag, ticket_bucket, stage_after)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, id, tag.toString(),
                    body.get("ticket_bucket"), body.get("stage_after"));
            }
        }

        // Upsert objection_summary aggregate
        updateObjectionSummary(tenantId, tagsRaw, "COMMITTED".equals(outcome));

        // Route follow-up arm via FastAPI
        routeFollowupArm(tenantId, id, tagsRaw, outcome);

        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/notes")
    public ResponseEntity<List<Map<String, Object>>> getNotes(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT cn.*, s.ticket_bucket
            FROM call_notes cn
            LEFT JOIN sessions s ON s.id = cn.session_id
            WHERE cn.tenant_id = ? AND cn.created_at >= NOW() - (? || ' days')::interval
            ORDER BY cn.created_at DESC LIMIT 200
            """, tenantId, String.valueOf(days)));
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> objections = jdbc.queryForList("""
            SELECT objection_tag, count_total, count_converted,
                ROUND(count_converted::numeric / NULLIF(count_total, 0), 4) AS conversion_rate
            FROM objection_summary WHERE tenant_id = ?
            ORDER BY count_total DESC
            """, tenantId);
        List<Map<String, Object>> notes = getNotes(tenantId, days).getBody();
        if (notes == null) notes = List.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mlRates = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/post-call/objection-rates",
                Map.of("call_notes", notes), Map.class);
            return ResponseEntity.ok(Map.of(
                "objection_rates", objections,
                "ml_rates",        mlRates != null ? mlRates : List.of()
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("objection_rates", objections, "ml_error", e.getMessage()));
        }
    }

    @GetMapping("/objections")
    public ResponseEntity<List<Map<String, Object>>> objections(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT objection_tag, count_total, count_converted,
                ROUND(count_converted::numeric / NULLIF(count_total, 0), 4) AS conversion_rate,
                last_seen_at
            FROM objection_summary WHERE tenant_id = ?
            ORDER BY count_total DESC
            """, tenantId));
    }

    private void updateObjectionSummary(UUID tenantId, Object tagsRaw, boolean converted) {
        if (!(tagsRaw instanceof List<?> tags)) return;
        int inc = converted ? 1 : 0;
        for (Object tag : tags) {
            if (tag == null) continue;
            jdbc.update("""
                INSERT INTO objection_summary
                    (tenant_id, objection_tag, count_total, count_converted, last_seen_at)
                VALUES (?, ?, 1, ?, NOW())
                ON CONFLICT (tenant_id, objection_tag) DO UPDATE SET
                    count_total     = objection_summary.count_total + 1,
                    count_converted = objection_summary.count_converted + ?,
                    last_seen_at    = NOW()
                """, tenantId, tag.toString(), inc, inc);
        }
    }

    private void routeFollowupArm(UUID tenantId, UUID noteId, Object tagsRaw, String outcome) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/post-call/route-followup",
                Map.of(
                    "objection_tags", tagsRaw instanceof List ? tagsRaw : List.of(),
                    "call_outcome",   outcome
                ),
                Map.class);
            if (result != null && result.get("arm") != null) {
                jdbc.update("""
                    UPDATE call_notes SET follow_up_arm_override = ?, signal_sent_at = NOW()
                    WHERE id = ?
                    """, result.get("arm"), noteId);
                log.debug("Post-call routing: note={} arm={}", noteId, result.get("arm"));
            }
        } catch (Exception e) {
            log.warn("Post-call routing failed for note {}: {}", noteId, e.getMessage());
        }
    }

    private String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); }
        catch (Exception e) { return "[]"; }
    }
}
