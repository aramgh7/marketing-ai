package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/benchmarks")
@RequiredArgsConstructor
@Slf4j
public class NicheBenchmarkController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @GetMapping("/niche/{nicheKey}")
    public ResponseEntity<List<Map<String, Object>>> getNicheBenchmarks(
            @PathVariable String nicheKey) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT metric_key, value, tenant_count, computed_at, data_window_days
            FROM niche_metric_benchmarks
            WHERE niche_key = ?
            AND computed_at >= NOW() - INTERVAL '48 hours'
            ORDER BY metric_key, computed_at DESC
            """, nicheKey));
    }

    @GetMapping("/my-performance")
    public ResponseEntity<Map<String, Object>> myPerformance(
            @RequestAttribute("tenantId") UUID tenantId) {
        List<String> niches = jdbc.queryForList(
            "SELECT COALESCE(niche_key, 'generic') FROM tenants WHERE id = ?",
            String.class, tenantId);
        if (niches.isEmpty()) return ResponseEntity.notFound().build();
        String niche = niches.get(0);

        Map<String, Object> tenantMetrics = computeTenantMetrics(tenantId);

        List<Map<String, Object>> benchmarks = jdbc.queryForList("""
            SELECT metric_key, value FROM niche_metric_benchmarks
            WHERE niche_key = ? AND value IS NOT NULL
            AND computed_at >= NOW() - INTERVAL '48 hours'
            ORDER BY computed_at DESC
            """, niche);

        try {
            Map<String, Object> req = Map.of(
                "tenant_metrics", tenantMetrics,
                "benchmarks",     benchmarks
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> comparison = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/attribution/benchmarks/compare", req, Map.class);
            return ResponseEntity.ok(Map.of(
                "niche",      niche,
                "my_metrics", tenantMetrics,
                "benchmarks", benchmarks,
                "comparison", comparison != null ? comparison : Map.of()
            ));
        } catch (Exception e) {
            log.warn("Benchmark compare failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of(
                "niche", niche, "my_metrics", tenantMetrics, "benchmarks", benchmarks));
        }
    }

    private Map<String, Object> computeTenantMetrics(UUID tenantId) {
        try {
            return jdbc.queryForMap("""
                SELECT
                    ROUND(COUNT(re.id) FILTER (WHERE re.is_quality_stage) * 1.0 /
                          NULLIF(COUNT(DISTINCT a.id), 0), 4) AS show_rate,
                    ROUND(COUNT(DISTINCT a.id) * 1.0 /
                          NULLIF(COUNT(DISTINCT fs.id), 0), 4) AS form_to_booked,
                    AVG(a.booking_distance_h)                   AS avg_booking_distance_h
                FROM tenants t
                LEFT JOIN appointments    a  ON a.tenant_id = t.id
                    AND a.created_at >= NOW() - INTERVAL '30 days'
                LEFT JOIN reward_events   re ON re.tenant_id = t.id
                    AND re.is_quality_stage = TRUE
                    AND re.rewarded_at >= NOW() - INTERVAL '30 days'
                LEFT JOIN form_submissions fs ON fs.tenant_id = t.id
                    AND fs.submitted_at >= NOW() - INTERVAL '30 days'
                WHERE t.id = ?
                """, tenantId);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
