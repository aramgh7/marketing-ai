package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Compliance by Construction + Moderation Probe + Reviewer Persona (vision §19.18.4; arch §16.17).
 */
@RestController
@RequestMapping("/api/v1/moderation")
@RequiredArgsConstructor
@Slf4j
public class ModerationController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @GetMapping("/results")
    public ResponseEntity<List<Map<String, Object>>> getResults(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT mr.*, c.name AS creative_name
            FROM moderation_results mr
            LEFT JOIN creatives c ON c.id = mr.creative_id
            WHERE mr.tenant_id = ? ORDER BY mr.checked_at DESC LIMIT 100
            """, tenantId));
    }

    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> record(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO moderation_results
                (id, tenant_id, creative_id, ad_set_id, probe_type, result, meta_rejection_code)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, tenantId,
            body.get("creative_id") != null ? UUID.fromString((String) body.get("creative_id")) : null,
            body.get("ad_set_id")   != null ? UUID.fromString((String) body.get("ad_set_id"))   : null,
            body.getOrDefault("probe_type", "meta_review"),
            body.get("result"),
            body.get("meta_rejection_code"));

        if ("REJECTED".equals(body.get("result")) && body.get("creative_id") != null) {
            jdbc.update("""
                UPDATE creatives SET client_label = 'loser' WHERE id = ? AND tenant_id = ?
                """, UUID.fromString((String) body.get("creative_id")), tenantId);
            log.info("Creative {} marked as loser after Meta rejection", body.get("creative_id"));
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/persona-score")
    public ResponseEntity<Map<String, Object>> personaScore(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        }

        List<Map<String, Object>> personas = jdbc.queryForList("""
            SELECT persona_key, rulebases, traits FROM reviewer_personas
            WHERE persona_key = 'META_REVIEWER' AND is_active = TRUE LIMIT 1
            """);
        if (personas.isEmpty()) {
            return ResponseEntity.ok(Map.of("verdict", "UNKNOWN", "note", "META_REVIEWER persona not seeded"));
        }

        List<Map<String, Object>> rulebases = jdbc.queryForList("""
            SELECT rulebase_key, rules::text AS rules FROM compliance_rulebases
            WHERE (tenant_id = ? OR tenant_id IS NULL) AND is_active = TRUE
            """, tenantId);

        Map<String, Object> complianceReq = Map.of("text", text, "rulebases", rulebases);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> complianceResult = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/compliance/check", complianceReq, Map.class);

            Map<String, Object> personaReq = Map.of(
                "text",               text,
                "persona",            personas.get(0),
                "compliance_results", complianceResult != null ? complianceResult : Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/persona/score", personaReq, Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of("verdict", "ERROR"));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("verdict", "UNKNOWN", "error", e.getMessage()));
        }
    }
}
