package com.marketingai.controller;

import com.marketingai.service.AutopilotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Autopilot Mode API (Phase 6-6).
 * Autopilot is OFF by default per §9.1. Human approval required before auto-deploy.
 * max_l1_triggers_per_day=3 hard cap; min_p_best_to_scale=0.95.
 */
@Slf4j
@RestController
@RequestMapping("/autopilot")
@RequiredArgsConstructor
public class AutopilotController {

    private final JdbcTemplate    jdbc;
    private final AutopilotService autopilotService;

    // ─── GET /settings ────────────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT enabled, auto_pause_burnout, auto_scale_winner, auto_retire_graveyard,
                       max_l1_triggers_per_day, min_p_best_to_scale, budget_scale_factor, updated_at
                FROM autopilot_settings
                WHERE tenant_id = ?
                """, tenantId);

        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "enabled", false,
                    "message", "Autopilot not configured — all actions require human approval"
            ));
        }
        return ResponseEntity.ok(rows.get(0));
    }

    // ─── PUT /settings ────────────────────────────────────────────────────────

    @PutMapping("/settings")
    public ResponseEntity<Void> updateSettings(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        jdbc.update("""
                INSERT INTO autopilot_settings (tenant_id, enabled,
                    auto_pause_burnout, auto_scale_winner, auto_retire_graveyard,
                    max_l1_triggers_per_day, min_p_best_to_scale, budget_scale_factor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET
                    enabled                  = EXCLUDED.enabled,
                    auto_pause_burnout        = EXCLUDED.auto_pause_burnout,
                    auto_scale_winner         = EXCLUDED.auto_scale_winner,
                    auto_retire_graveyard     = EXCLUDED.auto_retire_graveyard,
                    max_l1_triggers_per_day   = EXCLUDED.max_l1_triggers_per_day,
                    min_p_best_to_scale       = EXCLUDED.min_p_best_to_scale,
                    budget_scale_factor       = EXCLUDED.budget_scale_factor,
                    updated_at                = now()
                """,
                tenantId,
                boolOr(body.get("enabled"), false),
                boolOr(body.get("auto_pause_burnout"), false),
                boolOr(body.get("auto_scale_winner"), false),
                boolOr(body.get("auto_retire_graveyard"), false),
                intOr(body.get("max_l1_triggers_per_day"), 3),
                doubleOr(body.get("min_p_best_to_scale"), 0.95),
                doubleOr(body.get("budget_scale_factor"), 1.20)
        );

        return ResponseEntity.noContent().build();
    }

    // ─── GET /actions ─────────────────────────────────────────────────────────

    @GetMapping("/actions")
    public ResponseEntity<List<Map<String, Object>>> actions(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, action_type, target_id, payload, status, executed_at, created_at
                FROM autopilot_actions
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 100
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─── POST /actions/{id}/undo ──────────────────────────────────────────────

    @PostMapping("/actions/{id}/undo")
    public ResponseEntity<Void> undo(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        int updated = jdbc.update("""
                UPDATE autopilot_actions
                SET status = 'UNDONE', undone_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'EXECUTED'
                """, id, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Action not found or not in EXECUTED state");
        }
        return ResponseEntity.noContent().build();
    }

    // ─── POST /evaluate ───────────────────────────────────────────────────────

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(
            @RequestAttribute("tenantId") UUID tenantId) {

        Map<String, Object> result = autopilotService.evaluateActions(tenantId);
        return ResponseEntity.ok(result);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private boolean boolOr(Object v, boolean def) {
        if (v instanceof Boolean b) return b;
        return def;
    }

    private int intOr(Object v, int def) {
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private double doubleOr(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        return def;
    }
}
