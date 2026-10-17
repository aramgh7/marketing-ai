package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Personalization rules CRUD — replaces hard-coded CLUSTER_BLOCK_RULES (vision §5.4.1).
 * Marketer must approve rules; only approved (is_active=true) rules are used by /decide/personalise.
 * System NEVER generates new text — only reorders pre-existing blocks.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/personalization-rules")
@RequiredArgsConstructor
public class PersonalizationRulesController {

    private final JdbcTemplate jdbc;

    // ─── Create ───────────────────────────────────────────────────────────────

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        UUID tenantId = tenantId();
        String clusterLabel = required(body, "cluster_label");
        List<String> blockOrder = requireList(body, "allowed_block_order");
        String description = (String) body.get("description");

        // Upsert: one rule per cluster per tenant
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personalization_rules
                    (id, tenant_id, cluster_label, allowed_block_order, description,
                     is_active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, false, now(), now())
                ON CONFLICT (tenant_id, cluster_label) DO UPDATE SET
                    allowed_block_order = EXCLUDED.allowed_block_order,
                    description = EXCLUDED.description,
                    is_active = false,
                    approved_by = null,
                    approved_at = null,
                    updated_at = now()
                """,
                id, tenantId, clusterLabel, blockOrder.toArray(new String[0]), description);

        return getByCluster(tenantId, clusterLabel);
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT id, cluster_label, allowed_block_order, description,
                       is_active, approved_by, approved_at, created_at, updated_at
                FROM personalization_rules
                WHERE tenant_id = ?
                ORDER BY cluster_label
                """, tenantId());
    }

    // ─── Get ──────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, cluster_label, allowed_block_order, description,
                       is_active, approved_by, approved_at, created_at, updated_at
                FROM personalization_rules
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        return rows.get(0);
    }

    // ─── Update (resets approval) ─────────────────────────────────────────────

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID tenantId = tenantId();
        List<String> blockOrder = requireList(body, "allowed_block_order");
        String description = (String) body.get("description");

        int updated = jdbc.update("""
                UPDATE personalization_rules SET
                    allowed_block_order = ?,
                    description = ?,
                    is_active = false,
                    approved_by = null,
                    approved_at = null,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, blockOrder.toArray(new String[0]), description, id, tenantId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        return get(id);
    }

    // ─── Approve ──────────────────────────────────────────────────────────────

    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id) {
        UUID tenantId = tenantId();
        UUID userId = userId();
        int updated = jdbc.update("""
                UPDATE personalization_rules SET
                    is_active = true,
                    approved_by = ?,
                    approved_at = now(),
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, userId, id, tenantId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        log.info("Personalization rule {} approved by {}", id, userId);
        return get(id);
    }

    // ─── Deactivate ───────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID id) {
        jdbc.update("""
                UPDATE personalization_rules SET is_active = false, updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private UUID tenantId() {
        return UUID.fromString(TenantContext.get());
    }

    private UUID userId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required");
        return v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> requireList(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof List) return (List<String>) v;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " must be a list");
    }

    private Map<String, Object> getByCluster(UUID tenantId, String clusterLabel) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, cluster_label, allowed_block_order, description,
                       is_active, approved_by, approved_at, created_at, updated_at
                FROM personalization_rules
                WHERE tenant_id = ? AND cluster_label = ?
                """, tenantId, clusterLabel);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found");
        return rows.get(0);
    }
}
