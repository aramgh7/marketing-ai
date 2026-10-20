package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Agency Measurability (vision §19.18.7; arch §16.20).
 * Multi-tenant agency dashboard + creative source performance comparison.
 */
@RestController
@RequestMapping("/api/v1/agency")
@RequiredArgsConstructor
@Slf4j
public class AgencyController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/accounts")
    public ResponseEntity<Map<String, Object>> createAgency(
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO agency_accounts (id, name, slug, plan_tier)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (slug) DO NOTHING
            """, id, body.get("name"), body.get("slug"),
            body.getOrDefault("plan_tier", "starter"));
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/tenants")
    public ResponseEntity<List<Map<String, Object>>> getAgencyTenants(
            @RequestAttribute("tenantId") UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT agency_id FROM tenants WHERE id = ?", tenantId);
        if (rows.isEmpty() || rows.get(0).get("agency_id") == null)
            return ResponseEntity.ok(List.of());
        UUID agencyId = (UUID) rows.get(0).get("agency_id");
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id, name, niche_key, created_at
            FROM tenants WHERE agency_id = ? ORDER BY name
            """, agencyId));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> agencyRows = jdbc.queryForList(
            "SELECT agency_id FROM tenants WHERE id = ?", tenantId);
        if (agencyRows.isEmpty() || agencyRows.get(0).get("agency_id") == null)
            return ResponseEntity.ok(Map.of("note", "Tenant has no agency account"));

        UUID agencyId = (UUID) agencyRows.get(0).get("agency_id");
        List<Map<String, Object>> tenants = jdbc.queryForList(
            "SELECT id, name FROM tenants WHERE agency_id = ?", agencyId);

        List<Map<String, Object>> snapshots = tenants.stream().map(t -> {
            UUID tid = (UUID) t.get("id");
            try {
                return jdbc.queryForMap("""
                    SELECT
                        COUNT(DISTINCT s.id)                              AS lpv,
                        COUNT(re.id) FILTER (WHERE re.is_quality_stage)   AS held_calls
                    FROM sessions s
                    LEFT JOIN reward_events re
                        ON re.session_id = s.id AND re.tenant_id = s.tenant_id
                        AND re.rewarded_at >= NOW() - (? || ' days')::interval
                    WHERE s.tenant_id = ? AND s.created_at >= NOW() - (? || ' days')::interval
                    """, String.valueOf(days), tid, String.valueOf(days));
            } catch (Exception e) {
                return Map.<String, Object>of("lpv", 0, "held_calls", 0);
            }
        }).peek(snap -> snap.put("tenant_name", tenants.stream()
            .filter(t -> t.get("id").equals(snap.get("tenant_id")))
            .findFirst().map(t -> t.get("name")).orElse("unknown")))
          .collect(Collectors.toList());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/agency/aggregate-kpis",
                Map.of("tenant_snapshots", snapshots), Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of("snapshots", snapshots));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("snapshots", snapshots, "error", e.getMessage()));
        }
    }

    @GetMapping("/source-report")
    public ResponseEntity<Map<String, Object>> sourceReport(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> sources = jdbc.queryForList("""
            SELECT
                cs.id AS source_id, cs.name AS source_name, cs.kind,
                COUNT(DISTINCT s.id)                              AS lpv,
                COUNT(re.id) FILTER (WHERE re.is_quality_stage)   AS held_calls
            FROM creative_sources cs
            JOIN creatives c ON c.source_id = cs.id AND c.tenant_id = cs.tenant_id
            JOIN landing_variants lv ON lv.creative_id = c.id
            JOIN sessions s ON s.variant_id = lv.id AND s.tenant_id = cs.tenant_id
                AND s.created_at >= NOW() - (? || ' days')::interval
            LEFT JOIN reward_events re
                ON re.session_id = s.id AND re.tenant_id = s.tenant_id
                AND re.rewarded_at >= NOW() - (? || ' days')::interval
            WHERE cs.tenant_id = ?
            GROUP BY cs.id, cs.name, cs.kind
            ORDER BY held_calls DESC
            """, String.valueOf(days), String.valueOf(days), tenantId);

        if (sources.isEmpty()) return ResponseEntity.ok(Map.of("sources", List.of()));

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/agency/compare-sources",
                Map.of("source_rows", sources), Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of("sources", sources));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("sources", sources, "error", e.getMessage()));
        }
    }

    @PostMapping("/sources")
    public ResponseEntity<Map<String, Object>> createSource(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO creative_sources (id, tenant_id, name, kind, contact, notes)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, name) DO NOTHING
            """, id, tenantId,
            body.get("name"), body.get("kind"), body.get("contact"), body.get("notes"));
        return ResponseEntity.ok(Map.of("id", id));
    }

    @GetMapping("/sources")
    public ResponseEntity<List<Map<String, Object>>> listSources(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT * FROM creative_sources WHERE tenant_id = ? ORDER BY kind, name
            """, tenantId));
    }
}
