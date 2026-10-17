package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Creative Graveyard API (Phase 6-5).
 * Stores post-mortems for retired creatives.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/graveyard")
@RequiredArgsConstructor
public class GraveyardController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;

    // ─── GET / ────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "20") int limit) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, creative_id, failure_mode, cvr, benchmark_cvr,
                       sessions, ctr, created_at
                FROM creative_postmortems
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, Math.min(limit, 100));

        return ResponseEntity.ok(rows);
    }

    // ─── GET /{id} ────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT * FROM creative_postmortems WHERE id = ? AND tenant_id = ?
                    """, id, tenantId);
            return ResponseEntity.ok(row);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Post-mortem not found");
        }
    }

    // ─── POST /analyze/{creativeId} ───────────────────────────────────────────

    @PostMapping("/analyze/{creativeId}")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID creativeId,
            @RequestBody(required = false) Map<String, Object> body) {

        // Load creative atoms
        Map<String, Object> creative;
        try {
            creative = jdbc.queryForMap("""
                    SELECT atoms FROM creatives WHERE id = ? AND tenant_id = ?
                    """, creativeId, tenantId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creative not found");
        }

        // Load performance stats from thompson_arms
        Map<String, Object> arm;
        try {
            arm = jdbc.queryForMap("""
                    SELECT alpha, beta, alpha + beta AS trials
                    FROM thompson_arms WHERE creative_id = ? AND tenant_id = ?
                    """, creativeId, tenantId);
        } catch (Exception e) {
            arm = Map.of("alpha", 1.0, "beta", 1.0, "trials", 2.0);
        }

        double alpha   = ((Number) arm.get("alpha")).doubleValue();
        double beta    = ((Number) arm.get("beta")).doubleValue();
        double trials  = ((Number) arm.get("trials")).doubleValue();
        double cvr     = trials > 0 ? alpha / trials : 0.0;
        double benchCvr = body != null && body.containsKey("benchmark_cvr")
                ? ((Number) body.get("benchmark_cvr")).doubleValue() : 0.02;

        Map<String, Object> mlReq = new HashMap<>();
        mlReq.put("creative_id",    creativeId.toString());
        mlReq.put("atoms",          creative.getOrDefault("atoms", List.of()));
        mlReq.put("sessions",       (int) trials);
        mlReq.put("cvr",            cvr);
        mlReq.put("benchmark_cvr",  benchCvr);
        if (body != null && body.containsKey("top_performing_atoms")) {
            mlReq.put("top_performing_atoms", body.get("top_performing_atoms"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                "http://ml-service:8001/graveyard/postmortem", mlReq, Map.class);

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service unavailable");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> analysis = (Map<String, Object>) result.get("analysis");
        String postmortemMd = (String) result.get("postmortem_md");

        // Persist
        jdbc.update("""
                INSERT INTO creative_postmortems
                    (tenant_id, creative_id, failure_mode, cvr, benchmark_cvr, sessions, analysis, postmortem_md)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                tenantId, creativeId,
                analysis != null ? analysis.get("failure_mode") : "unknown",
                cvr, benchCvr, (int) trials,
                analysis != null ? analysis.toString() : "{}",
                postmortemMd);

        return ResponseEntity.ok(result);
    }
}
