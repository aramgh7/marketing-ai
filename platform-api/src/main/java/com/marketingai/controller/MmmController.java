package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Bayesian MMM endpoints (Phase 7, §17.3).
 * Delegates model computation to FastAPI /mmm/contributions and /mmm/optimize.
 */
@Slf4j
@RestController
@RequestMapping("/mmm")
@RequiredArgsConstructor
public class MmmController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    // ─── GET /latest ─────────────────────────────────────────────────────────

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, model_version, channels, contributions, saturation_curves,
                       budget_allocation, total_budget, date_range_start, date_range_end,
                       n_obs, fit_metrics, created_at
                FROM mmm_results
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, tenantId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No MMM results yet for this tenant");
        }
        return ResponseEntity.ok(rows.get(0));
    }

    // ─── POST /fit ────────────────────────────────────────────────────────────
    // Loads 90-day insights → calls FastAPI /mmm/contributions → stores result.

    @PostMapping("/fit")
    public ResponseEntity<Map<String, Object>> fit(
            @RequestAttribute("tenantId") UUID tenantId) {

        LocalDate end   = LocalDate.now();
        LocalDate start = end.minusDays(90);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT channel, SUM(spend) AS total_spend, SUM(conversions) AS total_conversions,
                       SUM(impressions) AS total_impressions
                FROM channel_insights_history
                WHERE tenant_id = ?
                  AND date BETWEEN ? AND ?
                GROUP BY channel
                """, tenantId, start, end);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No channel insights data for the last 90 days");
        }

        // Build spend_data per channel (daily series — simplified: one value per channel)
        Map<String, List<Double>> spendData = new LinkedHashMap<>();
        Map<String, Double> contributions = new LinkedHashMap<>();
        double totalSpend = rows.stream()
                .mapToDouble(r -> ((Number) r.get("total_spend")).doubleValue()).sum();

        for (Map<String, Object> row : rows) {
            String channel = (String) row.get("channel");
            double spend   = ((Number) row.get("total_spend")).doubleValue();
            spendData.put(channel, List.of(spend));
            contributions.put(channel, totalSpend > 0 ? spend / totalSpend : 0.0);
        }

        Map<String, Object> mlReq = Map.of(
                "spend_data",    spendData,
                "contributions", contributions
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/mmm/contributions", mlReq, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MMM service error: " + e.getMessage());
        }

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MMM service returned null");
        }

        UUID resultId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mmm_results
                    (id, tenant_id, model_version, channels, contributions, saturation_curves,
                     budget_allocation, total_budget, date_range_start, date_range_end, n_obs, fit_metrics)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb)
                """,
                resultId, tenantId, "v1",
                toJson(mlResult.get("channels")),
                toJson(mlResult.get("contributions")),
                toJson(mlResult.getOrDefault("saturation_curves", Map.of())),
                toJson(mlResult.getOrDefault("budget_allocation", Map.of())),
                BigDecimal.valueOf(totalSpend),
                start, end,
                rows.size(),
                "{}"
        );

        Map<String, Object> resp = new LinkedHashMap<>(mlResult);
        resp.put("result_id", resultId);
        return ResponseEntity.ok(resp);
    }

    // ─── GET /budget-recommendation ──────────────────────────────────────────

    @GetMapping("/budget-recommendation")
    public ResponseEntity<Map<String, Object>> budgetRecommendation(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "10000") double totalBudget) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT contributions, channels
                FROM mmm_results
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, tenantId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No MMM results yet — run /fit first");
        }

        @SuppressWarnings("unchecked")
        Map<String, Double> contributions = (Map<String, Double>) rows.get(0).get("contributions");

        // Build default saturation params from contributions (simple heuristic)
        Map<String, Map<String, Object>> satParams = new LinkedHashMap<>();
        if (contributions != null) {
            for (Map.Entry<String, Double> e : contributions.entrySet()) {
                satParams.put(e.getKey(), Map.of(
                        "half_saturation", 5000.0,
                        "slope",           1.0,
                        "decay_rate",      0.5,
                        "roi_scale",       e.getValue() > 0 ? 1.0 / e.getValue() : 1.0
                ));
            }
        }

        Map<String, Object> mlReq = Map.of(
                "saturation_params", satParams,
                "total_budget",      totalBudget
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/mmm/optimize", mlReq, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "MMM service error: " + e.getMessage());
        }

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MMM service returned null");
        }

        return ResponseEntity.ok(mlResult);
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
