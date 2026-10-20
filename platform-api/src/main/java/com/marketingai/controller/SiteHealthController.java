package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/site-health")
@RequiredArgsConstructor
public class SiteHealthController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getIncidents(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> incidents = jdbc.queryForList("""
                SELECT id, variant_id, cause, opened_at, resolved_at, consecutive_fails, detail
                FROM site_health_incidents
                WHERE tenant_id = ?
                ORDER BY opened_at DESC
                LIMIT 50
                """, tenantId);

        List<Map<String, Object>> recentChecks = jdbc.queryForList("""
                SELECT variant_id, check_type, status, latency_ms, http_status, checked_at
                FROM site_health_checks
                WHERE tenant_id = ?
                  AND checked_at > now() - interval '1 hour'
                ORDER BY checked_at DESC
                LIMIT 100
                """, tenantId);

        return ResponseEntity.ok(Map.of(
                "incidents", incidents,
                "recent_checks", recentChecks));
    }

    @PostMapping("/{incidentId}/resolve")
    public ResponseEntity<Void> resolveIncident(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID incidentId) {

        int updated = jdbc.update("""
                UPDATE site_health_incidents
                SET resolved_at = now()
                WHERE id = ? AND tenant_id = ? AND resolved_at IS NULL
                """, incidentId, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident not found or already resolved");
        }
        return ResponseEntity.noContent().build();
    }
}
