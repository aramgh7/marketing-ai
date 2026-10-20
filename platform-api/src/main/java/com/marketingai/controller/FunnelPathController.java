package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Funnel Path as Thompson Arm (vision §19.18.1; arch §16.14).
 * 4 paths for Client #1 investor funnel.
 */
@RestController
@RequestMapping("/funnel-paths")
@RequiredArgsConstructor
@Slf4j
public class FunnelPathController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT fp.*, COALESCE(ta.alpha, 1.0) AS alpha, COALESCE(ta.beta, 1.0) AS beta
            FROM funnel_paths fp
            LEFT JOIN thompson_arms ta ON ta.variant_id = fp.id
                AND ta.tenant_id = fp.tenant_id AND ta.reward_event = 'CALL_HELD'
            WHERE fp.tenant_id = ? ORDER BY fp.created_at
            """, tenantId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO funnel_paths
                (id, tenant_id, path_key, path_label, description, is_control, ghl_workflow_id, config)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (tenant_id, path_key) DO UPDATE SET
                path_label = EXCLUDED.path_label, config = EXCLUDED.config
            """, id, tenantId,
            body.get("path_key"), body.get("path_label"), body.get("description"),
            body.getOrDefault("is_control", false),
            body.get("ghl_workflow_id"), toJson(body.getOrDefault("config", Map.of())));
        return ResponseEntity.ok(Map.of("id", id));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {
        jdbc.update("""
            UPDATE funnel_paths SET status = 'ACTIVE'
            WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW'
            """, id, tenantId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/seed/client1")
    public ResponseEntity<Void> seedClient1(@RequestAttribute("tenantId") UUID tenantId) {
        Object[][] paths = {
            {"CALL_4D",          "Book a call (4-day window)", true,
             "Evergreen default. Book → coordinator → call in 4 days."},
            {"MATERIALS_FIRST",  "Materials first, then invite", false,
             "Founder video + data-room-light → engagement gate → call invite."},
            {"TIERED_BY_TICKET", "Tiered by investment size", false,
             "<$50K webinar / $50-250K 1:1 / >$250K founder direct call."},
            {"CALL_24H",         "24-hour call window", false,
             "Coordinator offers slot within 24 h; team call follows."},
        };
        for (Object[] p : paths) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO funnel_paths
                    (id, tenant_id, path_key, path_label, is_control, description, status)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT')
                ON CONFLICT (tenant_id, path_key) DO NOTHING
                """, id, tenantId, p[0], p[1], p[2], p[3]);
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> paths = jdbc.queryForList(
            "SELECT id, path_key, path_label, status, is_control FROM funnel_paths WHERE tenant_id = ?",
            tenantId);
        List<Map<String, Object>> metrics = jdbc.queryForList("""
            SELECT
                COALESCE(s.funnel_path_id::text, 'null') AS path_id,
                COUNT(DISTINCT s.id)                     AS page_views,
                COUNT(re.id) FILTER (WHERE re.is_quality_stage) AS held_calls
            FROM sessions s
            LEFT JOIN reward_events re ON re.session_id = s.id AND re.tenant_id = s.tenant_id
                AND re.rewarded_at >= NOW() - (? || ' days')::interval
            WHERE s.tenant_id = ? AND s.created_at >= NOW() - (? || ' days')::interval
            GROUP BY COALESCE(s.funnel_path_id::text, 'null')
            """, String.valueOf(days), tenantId, String.valueOf(days));
        return ResponseEntity.ok(Map.of("paths", paths, "metrics", metrics, "days", days));
    }

    private String toJson(Object o) {
        try { return new ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }
}
