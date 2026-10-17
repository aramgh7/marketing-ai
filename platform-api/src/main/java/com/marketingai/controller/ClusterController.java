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
 * Behavioral Cluster endpoints (Phase 5-1, §7.3 Этап 1).
 *
 * Spring Boot stores cluster metadata in behavior_clusters and session_propensity.
 * Heavy K-means fitting is delegated to FastAPI /cluster/fit.
 * Online assignment (real-time per session) delegates to FastAPI /cluster/assign.
 *
 * Security constraints:
 *   - Only sessions with consent_level='full' and is_suspicious=false are sent to /cluster/fit
 *   - Personalisation group is enforced: 5–10% of sessions stay in 'control' (§7.4)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/cluster")
@RequiredArgsConstructor
public class ClusterController {

    private final RestTemplate   restTemplate;
    private final AppProperties  appProperties;
    private final JdbcTemplate   jdbc;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/cluster/labels — return stored cluster labels for tenant
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/labels")
    public ResponseEntity<List<Map<String, Object>>> getClusterLabels(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cluster_idx, label, hypothesis, session_count, cvr_estimate, fitted_at
                FROM behavior_clusters
                WHERE tenant_id = ?
                ORDER BY cluster_idx
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/cluster/assign — assign one session to a cluster (real-time)
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/assign")
    public ResponseEntity<Map<String, Object>> assignSession(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String sessionId = (String) body.get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session_id required");
        }

        // Enforce consent gate (§12.1a): only full-consent sessions get personalisation
        String consentLevel = jdbc.queryForObject("""
                SELECT COALESCE(consent_level, 'none')
                FROM sessions WHERE id = ? AND tenant_id = ?
                """, String.class, sessionId, tenantId);

        if (!"full".equals(consentLevel)) {
            return ResponseEntity.ok(Map.of(
                    "cluster_idx",  -1,
                    "rule_label",   "no_consent",
                    "block_order",  body.getOrDefault("default_block_order", List.of())
            ));
        }

        // Load stored centroids for this tenant
        List<Map<String, Object>> centroidRows = jdbc.queryForList("""
                SELECT cluster_idx, centroid, label FROM behavior_clusters
                WHERE tenant_id = ? ORDER BY cluster_idx
                """, tenantId);

        if (centroidRows.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "cluster_idx", -1,
                    "rule_label",  "no_model",
                    "block_order", body.getOrDefault("default_block_order", List.of())
            ));
        }

        // Forward to FastAPI for online assignment
        Map<String, Object> mlBody = new HashMap<>();
        mlBody.put("tenant_id",       tenantId.toString());
        mlBody.put("session",         body.getOrDefault("session", Map.of()));
        mlBody.put("events",          body.getOrDefault("events", List.of()));
        mlBody.put("centroid_dicts",  buildCentroidDicts(centroidRows));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        String url = appProperties.getMlServiceUrl() + "/cluster/assign";
        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                url, new HttpEntity<>(mlBody, headers), Map.class);

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service returned null");
        }

        int clusterIdx = ((Number) result.getOrDefault("cluster_idx", -1)).intValue();
        String ruleLabel = (String) result.getOrDefault("rule_label", "researcher");

        // Persist cluster assignment back to sessions table
        jdbc.update("""
                UPDATE sessions SET cluster_id = ?, cluster_label = ?
                WHERE id = ? AND tenant_id = ?
                """, clusterIdx >= 0 ? clusterIdx : null, ruleLabel, sessionId, tenantId);

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/cluster/personalise — get personalised block order
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/personalise")
    public ResponseEntity<Map<String, Object>> personalise(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String sessionId = (String) body.get("session_id");

        // Enforce personalisation control group (§7.4): 5–10% stay unPersonalised
        String group = assignPersonalisationGroup(tenantId, sessionId);
        if ("control".equals(group)) {
            return ResponseEntity.ok(Map.of(
                    "cluster_idx",   -1,
                    "cluster_label", "control",
                    "block_order",   body.getOrDefault("default_block_order", List.of())
            ));
        }

        // Enforce consent gate
        if (sessionId != null) {
            try {
                String consent = jdbc.queryForObject("""
                        SELECT COALESCE(consent_level, 'none')
                        FROM sessions WHERE id = ? AND tenant_id = ?
                        """, String.class, sessionId, tenantId);
                if (!"full".equals(consent)) {
                    return ResponseEntity.ok(Map.of(
                            "cluster_idx",   -1,
                            "cluster_label", "no_consent",
                            "block_order",   body.getOrDefault("default_block_order", List.of())
                    ));
                }
            } catch (Exception ignored) {
                // session not found yet — proceed without consent gate
            }
        }

        List<Map<String, Object>> centroidRows = jdbc.queryForList("""
                SELECT cluster_idx, centroid, label FROM behavior_clusters
                WHERE tenant_id = ? ORDER BY cluster_idx
                """, tenantId);

        if (centroidRows.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "cluster_idx",   -1,
                    "cluster_label", "no_model",
                    "block_order",   body.getOrDefault("default_block_order", List.of())
            ));
        }

        List<String> clusterLabels = centroidRows.stream()
                .map(r -> (String) r.getOrDefault("label", "researcher"))
                .toList();

        Map<String, Object> mlBody = new HashMap<>();
        mlBody.put("tenant_id",           tenantId.toString());
        mlBody.put("session",             body.getOrDefault("session", Map.of()));
        mlBody.put("events",              body.getOrDefault("events", List.of()));
        mlBody.put("centroid_dicts",      buildCentroidDicts(centroidRows));
        mlBody.put("cluster_labels",      clusterLabels);
        mlBody.put("default_block_order", body.getOrDefault("default_block_order", List.of()));
        mlBody.put("allowed_block_ids",   body.get("allowed_block_ids"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        String url = appProperties.getMlServiceUrl() + "/cluster/personalise";
        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                url, new HttpEntity<>(mlBody, headers), Map.class);

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service returned null");
        }

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Unpack stored JSONB centroid column into list of feature maps. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildCentroidDicts(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object centroidObj = row.get("centroid");
            if (centroidObj instanceof Map<?,?> map) {
                Map<String, Object> cd = new HashMap<>((Map<String, Object>) map);
                cd.put("_cluster_idx", ((Number) row.getOrDefault("cluster_idx", 0)).intValue());
                result.add(cd);
            }
        }
        return result;
    }

    /**
     * Deterministically assign personalisation group for a session.
     * Uses hash of session_id so assignment is stable across requests.
     * Control group is ~7.5% of sessions (§7.4: 5–10%).
     */
    private String assignPersonalisationGroup(UUID tenantId, String sessionId) {
        if (sessionId == null) return "personalised";
        // Mask sign bit before mod to avoid Integer.MIN_VALUE overflow from Math.abs()
        int hash = ((tenantId.toString() + sessionId).hashCode() & Integer.MAX_VALUE) % 1000;
        return hash < 75 ? "control" : "personalised";
    }
}
