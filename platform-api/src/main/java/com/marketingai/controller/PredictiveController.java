package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Predictive Budget Curve API (Phase 6-3, §8.4).
 * Loads alpha/beta from thompson_arms (read-only — FastAPI is sole writer).
 * Delegates Monte Carlo simulation to ml-service /predict/budget-curve.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/predict")
@RequiredArgsConstructor
public class PredictiveController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;

    @PostMapping("/budget-curve")
    public ResponseEntity<Map<String, Object>> budgetCurve(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String creativeId = (String) body.get("creative_id");
        if (creativeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creative_id required");
        }

        // Load alpha/beta from thompson_arms (Spring Boot never writes these — FastAPI only)
        Map<String, Object> arm;
        try {
            arm = jdbc.queryForMap("""
                    SELECT alpha, beta
                    FROM thompson_arms
                    WHERE tenant_id = ? AND creative_id = ?::uuid
                    """, tenantId, creativeId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No Thompson arm found for creative " + creativeId);
        }

        double alpha = ((Number) arm.get("alpha")).doubleValue();
        double beta  = ((Number) arm.get("beta")).doubleValue();

        double sessionsPerDollar = body.containsKey("sessions_per_dollar")
                ? ((Number) body.get("sessions_per_dollar")).doubleValue()
                : 10.0;

        @SuppressWarnings("unchecked")
        List<Number> rawPoints = (List<Number>) body.getOrDefault("budget_points",
                List.of(100, 200, 500, 1000, 2000, 5000));
        List<Double> budgetPoints = rawPoints.stream().map(Number::doubleValue).toList();

        Map<String, Object> mlReq = Map.of(
                "alpha",               alpha,
                "beta",                beta,
                "sessions_per_dollar", sessionsPerDollar,
                "budget_points",       budgetPoints,
                "n_mc",                body.getOrDefault("n_mc", 2000)
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    "http://ml-service:8001/predict/budget-curve", mlReq, Map.class);
            return ResponseEntity.ok(Objects.requireNonNullElse(result, Map.of()));
        } catch (Exception e) {
            log.error("Budget curve ML call failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service unavailable");
        }
    }
}
