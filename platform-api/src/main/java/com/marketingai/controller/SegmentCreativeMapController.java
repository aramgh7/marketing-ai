package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Segment-to-Creative mapping (Block F §1).
 * Maps cluster segments to approved creative variants per channel.
 * Used by the bid layer to pre-select creatives before Thompson sampling.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/segment-creative-map")
@RequiredArgsConstructor
public class SegmentCreativeMapController {

    private final JdbcTemplate jdbc;

    // ─── Create ───────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        String clusterLabel = required(body, "cluster_label");
        String creativeIdStr = required(body, "creative_id");
        String channel = ((String) body.getOrDefault("channel", "META")).toUpperCase();
        int priority = body.containsKey("priority") ? ((Number) body.get("priority")).intValue() : 0;

        UUID creativeId;
        try {
            creativeId = UUID.fromString(creativeIdStr);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid creative_id UUID");
        }

        Map<String, Object> row = jdbc.queryForMap("""
            INSERT INTO segment_creative_map (tenant_id, cluster_label, creative_id, channel, priority)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, tenant_id, cluster_label, creative_id::text, channel, priority, is_active, created_at
            """, tenantId, clusterLabel, creativeId, channel, priority);

        log.info("SegmentCreativeMap created: {} → {} [{}] (tenant {})", clusterLabel, creativeId, channel, tenantId);
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    // ─── List by cluster ──────────────────────────────────────────────────────

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String cluster_label,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "true") boolean active_only) {

        UUID tenantId = UUID.fromString(TenantContext.get());
        StringBuilder sql = new StringBuilder("""
            SELECT id, cluster_label, creative_id::text, channel, priority, is_active, created_at
            FROM segment_creative_map
            WHERE tenant_id = ?
            """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (cluster_label != null) { sql.append(" AND cluster_label = ?"); params.add(cluster_label); }
        if (channel != null) { sql.append(" AND channel = ?"); params.add(channel.toUpperCase()); }
        if (active_only) sql.append(" AND is_active = TRUE");
        sql.append(" ORDER BY cluster_label, priority DESC");

        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    // ─── Update ───────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id,
                                       @RequestBody Map<String, Object> body) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        requireOwnership(id, tenantId);

        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (body.containsKey("priority"))   { sets.add("priority = ?");   params.add(((Number)body.get("priority")).intValue()); }
        if (body.containsKey("is_active"))  { sets.add("is_active = ?");  params.add(body.get("is_active")); }
        if (body.containsKey("channel"))    { sets.add("channel = ?");    params.add(((String)body.get("channel")).toUpperCase()); }

        if (sets.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No updatable fields");

        sets.add("updated_at = NOW()");
        params.add(id);
        params.add(tenantId);

        jdbc.update("UPDATE segment_creative_map SET " + String.join(", ", sets)
                + " WHERE id = ? AND tenant_id = ?", params.toArray());

        return jdbc.queryForMap("""
            SELECT id, cluster_label, creative_id::text, channel, priority, is_active, updated_at
            FROM segment_creative_map WHERE id = ? AND tenant_id = ?
            """, id, tenantId);
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        requireOwnership(id, tenantId);
        jdbc.update("DELETE FROM segment_creative_map WHERE id = ? AND tenant_id = ?", id, tenantId);
        return ResponseEntity.noContent().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || v.toString().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing required field: " + key);
        return v.toString();
    }

    private void requireOwnership(UUID id, UUID tenantId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM segment_creative_map WHERE id = ? AND tenant_id = ?",
            Integer.class, id, tenantId);
        if (count == null || count == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mapping not found");
    }
}
