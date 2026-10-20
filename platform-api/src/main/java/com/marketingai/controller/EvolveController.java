package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Evolutionary Creative Generation API (Phase 6-4).
 * Generates, approves, and tracks evolved creative candidates.
 */
@Slf4j
@RestController
@RequestMapping("/evolve")
@RequiredArgsConstructor
public class EvolveController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    // ─── POST /generate ───────────────────────────────────────────────────────

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        // Load top arms with atoms from DB
        List<Map<String, Object>> arms = jdbc.queryForList("""
                SELECT ta.id::text AS id, ta.alpha, ta.beta, c.atoms
                FROM thompson_arms ta
                JOIN creatives c ON c.id = ta.creative_id
                WHERE ta.tenant_id = ?
                  AND ta.alpha + ta.beta >= 50
                ORDER BY ta.alpha / (ta.alpha + ta.beta) DESC
                LIMIT 10
                """, tenantId);

        if (arms.size() < 2) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Need at least 2 arms with ≥50 trials to evolve");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atomPool = (List<Map<String, Object>>) body.getOrDefault("atom_pool", List.of());
        int nCandidates = body.containsKey("n_candidates")
                ? ((Number) body.get("n_candidates")).intValue() : 5;

        Map<String, Object> mlReq = Map.of(
                "arms",          arms,
                "atom_pool",     atomPool,
                "n_candidates",  nCandidates,
                "mutation_rate", body.getOrDefault("mutation_rate", 0.15)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/evolve/candidates", mlReq, Map.class);

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service unavailable");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) mlResult.get("candidates");
        List<String> savedIds = new ArrayList<>();

        if (candidates != null) {
            for (Map<String, Object> c : candidates) {
                String id = jdbc.queryForObject("""
                        INSERT INTO evolution_candidates
                            (tenant_id, parent_a_id, parent_b_id, atoms, status)
                        VALUES (?, ?::uuid, ?::uuid, ?::jsonb, 'PROPOSED')
                        RETURNING id::text
                        """,
                        String.class,
                        tenantId,
                        c.getOrDefault("parent_ids[0]", null),
                        c.getOrDefault("parent_ids[1]", null),
                        c.getOrDefault("atoms", "[]").toString());
                savedIds.add(id);
            }
        }

        return ResponseEntity.ok(Map.of("candidate_ids", savedIds, "count", savedIds.size()));
    }

    // ─── GET /candidates ──────────────────────────────────────────────────────

    @GetMapping("/candidates")
    public ResponseEntity<List<Map<String, Object>>> candidates(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "PROPOSED") String status) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, parent_a_id, parent_b_id, atoms, status, created_at
                FROM evolution_candidates
                WHERE tenant_id = ? AND status = ?
                ORDER BY created_at DESC
                LIMIT 50
                """, tenantId, status);

        return ResponseEntity.ok(rows);
    }

    // ─── POST /{id}/approve ───────────────────────────────────────────────────

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestAttribute("userId") UUID userId) {

        int updated = jdbc.update("""
                UPDATE evolution_candidates
                SET status = 'APPROVED', approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'PROPOSED'
                """, userId, id, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found or not in PROPOSED state");
        }
        return ResponseEntity.noContent().build();
    }

    // ─── POST /{id}/reject ────────────────────────────────────────────────────

    @PostMapping("/{id}/reject")
    public ResponseEntity<Void> reject(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        int updated = jdbc.update("""
                UPDATE evolution_candidates
                SET status = 'REJECTED', updated_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'PROPOSED'
                """, id, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Candidate not found or not in PROPOSED state");
        }
        return ResponseEntity.noContent().build();
    }
}
