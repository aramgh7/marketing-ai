package com.marketingai.controller;

import com.marketingai.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/experiments")
@RequiredArgsConstructor
public class ExperimentController {

    private final ExperimentService experimentService;
    private final JdbcTemplate      jdbc;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) String status) {
        String sql = "SELECT * FROM experiments WHERE tenant_id = ?";
        List<Object> params = new ArrayList<>(List.of((Object) tenantId));
        if (status != null) { sql += " AND status = ?"; params.add(status); }
        sql += " ORDER BY created_at DESC";
        return ResponseEntity.ok(jdbc.queryForList(sql, params.toArray()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestAttribute("userId")   UUID userId,
            @RequestBody Map<String, Object> body) {
        UUID id = experimentService.createExperiment(tenantId, userId, body);
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<Void> submit(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {
        experimentService.submitForReview(tenantId, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestAttribute("userId")   UUID userId) {
        experimentService.approve(tenantId, id, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> start(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {
        experimentService.start(tenantId, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/results")
    public ResponseEntity<Map<String, Object>> results(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {
        Map<String, Object> exp = jdbc.queryForMap(
            "SELECT * FROM experiments WHERE id = ? AND tenant_id = ?", id, tenantId);
        List<Map<String, Object>> arms = jdbc.queryForList("""
            SELECT lv.id AS variant_id, lv.name, lv.is_control, lv.status,
                   COALESCE(ta.alpha, 1.0) AS alpha, COALESCE(ta.beta, 1.0) AS beta
            FROM landing_variants lv
            LEFT JOIN thompson_arms ta ON ta.variant_id = lv.id AND ta.tenant_id = ?
                AND ta.reward_event = 'quality_gate_pass'
            WHERE lv.experiment_id = ?
            ORDER BY lv.is_control DESC
            """, tenantId, id);
        return ResponseEntity.ok(Map.of("experiment", exp, "arms", arms));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<Map<String, Object>>> events(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT * FROM experiment_events
            WHERE experiment_id = ? AND tenant_id = ?
            ORDER BY created_at DESC
            """, id, tenantId));
    }
}
