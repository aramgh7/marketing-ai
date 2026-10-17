package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 8-11: Creative Production & Exploration Set-up (vision §19.10).
 * Import client dashboard labels as informed priors; manage creative aliases.
 */
@RestController
@RequestMapping("/api/v1/creative-setup")
@RequiredArgsConstructor
@Slf4j
public class CreativeSetupController {

    private final JdbcTemplate jdbc;

    /**
     * Bulk-import client labels: winner → Beta(5,1), loser → Beta(1,5),
     * questionable → Beta(2,2). Unknown labels default to Beta(1,1).
     *
     * Body: { "entries": [ { "creative_id": "...", "client_label": "winner" }, ... ] }
     */
    @PostMapping("/import-labels")
    public ResponseEntity<Map<String, Object>> importLabels(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
            (List<Map<String, Object>>) body.getOrDefault("entries", List.of());

        int updated = 0;
        for (Map<String, Object> entry : entries) {
            String rawId = (String) entry.get("creative_id");
            String label = (String) entry.getOrDefault("client_label", "");
            if (rawId == null) continue;

            UUID creativeId;
            try { creativeId = UUID.fromString(rawId); } catch (Exception e) { continue; }

            double alpha = 1.0, beta = 1.0;
            switch (label.toLowerCase()) {
                case "winner"       -> { alpha = 5.0; beta  = 1.0; }
                case "loser"        -> { alpha = 1.0; beta  = 5.0; }
                case "questionable" -> { alpha = 2.0; beta  = 2.0; }
            }

            int rows = jdbc.update("""
                UPDATE creatives
                SET client_label = ?, informed_prior_alpha = ?, informed_prior_beta = ?
                WHERE id = ? AND tenant_id = ?
                """, label.toLowerCase(), alpha, beta, creativeId, tenantId);
            updated += rows;
        }

        return ResponseEntity.ok(Map.of("imported", entries.size(), "updated", updated));
    }

    /**
     * List creatives with their informed priors and graveyard status.
     * Useful for confirming import results.
     */
    @GetMapping("/labels")
    public ResponseEntity<List<Map<String, Object>>> listLabels(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT c.id, c.name, c.client_label,
                   c.informed_prior_alpha, c.informed_prior_beta,
                   g.id IS NOT NULL AS in_graveyard, g.reason AS graveyard_reason
            FROM creatives c
            LEFT JOIN graveyard_entries g
                ON g.creative_id = c.id AND g.tenant_id = c.tenant_id
            WHERE c.tenant_id = ?
            ORDER BY c.client_label NULLS LAST, c.name
            """, tenantId));
    }

    // ── Creative Aliases ───────────────────────────────────────────────────────

    @GetMapping("/aliases")
    public ResponseEntity<List<Map<String, Object>>> listAliases(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) UUID creativeId) {
        if (creativeId != null) {
            return ResponseEntity.ok(jdbc.queryForList("""
                SELECT id, creative_id, alias_type, label, notes, created_at
                FROM creative_aliases
                WHERE tenant_id = ? AND creative_id = ?
                ORDER BY created_at
                """, tenantId, creativeId));
        }
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id, creative_id, alias_type, label, notes, created_at
            FROM creative_aliases
            WHERE tenant_id = ?
            ORDER BY creative_id, created_at
            """, tenantId));
    }

    @PostMapping("/aliases")
    public ResponseEntity<Map<String, Object>> createAlias(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String rawCreativeId = (String) body.get("creative_id");
        String aliasType     = (String) body.getOrDefault("alias_type", "hook_variant");
        String label         = (String) body.getOrDefault("label", "");
        String notes         = (String) body.get("notes");

        if (rawCreativeId == null || label.isBlank())
            return ResponseEntity.badRequest().build();

        UUID creativeId;
        try { creativeId = UUID.fromString(rawCreativeId); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        // Verify creative belongs to this tenant
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM creatives WHERE id = ? AND tenant_id = ?",
            Integer.class, creativeId, tenantId);
        if (count == null || count == 0)
            return ResponseEntity.notFound().build();

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO creative_aliases (id, tenant_id, creative_id, alias_type, label, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, tenantId, creativeId, aliasType, label, notes);

        return ResponseEntity.ok(Map.of("id", id.toString(), "created", true));
    }

    // ── Graveyard Entry (wrapper for Phase 3-17) ───────────────────────────────

    /**
     * Adds a graveyard entry for a blocked/disabled creative with a reason.
     * Assumes graveyard_entries table exists from earlier phase.
     */
    @PostMapping("/graveyard-entry")
    public ResponseEntity<Map<String, Object>> addGraveyardEntry(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String rawCreativeId = (String) body.get("creative_id");
        String reason        = (String) body.getOrDefault("reason", "blocked_by_client");
        String notes         = (String) body.get("notes");

        if (rawCreativeId == null)
            return ResponseEntity.badRequest().build();

        UUID creativeId;
        try { creativeId = UUID.fromString(rawCreativeId); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        // creative_postmortems is the graveyard table (Phase 6-5)
        String analysis = "{\"source\":\"client_label_import\",\"reason\":\"" + reason + "\"}";
        jdbc.update("""
            INSERT INTO creative_postmortems
                (tenant_id, creative_id, failure_mode, cvr, benchmark_cvr, sessions, analysis, postmortem_md)
            VALUES (?, ?, ?, 0, 0, 0, ?::jsonb, ?)
            ON CONFLICT DO NOTHING
            """, tenantId, creativeId, reason, analysis, notes);

        return ResponseEntity.ok(Map.of("created", true));
    }
}
