package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Off-Policy Evaluation gate (3-18).
 * GET  /api/v1/benchmark     — latest OPE run for current tenant
 * POST /api/v1/benchmark/evaluate — trigger OPE, save result
 */
@Slf4j
@RestController
@RequestMapping("/ope")
@RequiredArgsConstructor
public class OpeController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @GetMapping
    public Map<String, Object> getLatestBenchmark() {
        UUID tenantId = currentTenant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, policy_version, logged_from, logged_to,
                       ips, snips, dr, ci_low, ci_high, ess, clipped_mass, verdict, created_at
                FROM ope_runs
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, tenantId);

        if (rows.isEmpty()) {
            return Map.of("verdict", "INSUFFICIENT_DATA", "message", "No OPE runs yet.");
        }
        return rows.get(0);
    }

    @PostMapping("/evaluate")
    public Map<String, Object> evaluate() {
        UUID tenantId = currentTenant();

        // Fetch decisions from decision_log
        List<Map<String, Object>> decisions = jdbc.queryForList("""
                SELECT dl.chosen_variant_id AS variant_id,
                       COALESCE((SELECT (c->>'propensity')::float8 FROM jsonb_array_elements(dl.candidates) c
                                 WHERE c->>'variant_id' = dl.chosen_variant_id::text LIMIT 1), 1e-6) AS logged_prob,
                       CASE WHEN EXISTS (SELECT 1 FROM thompson_reward_log r
                                         WHERE r.decision_id = dl.id AND r.field_updated = 'alpha')
                            THEN 1.0 ELSE 0.0 END AS reward
                FROM decision_log dl
                WHERE dl.tenant_id = ?
                  AND dl.decided_at > now() - interval '30 days'
                LIMIT 5000
                """, tenantId);

        if (decisions.size() < 10) {
            return Map.of("verdict", "INSUFFICIENT_DATA",
                    "message", "Need at least 10 decision_log rows.");
        }

        // Fetch current policy probs from thompson_arms
        List<Map<String, Object>> arms = jdbc.queryForList("""
                SELECT ta.variant_id, ta.alpha, ta.beta
                FROM thompson_arms ta
                JOIN landing_variants lv ON lv.id = ta.variant_id
                WHERE lv.tenant_id = ?
                """, tenantId);

        Map<String, Double> currentPolicyProbs = new HashMap<>();
        for (Map<String, Object> arm : arms) {
            String vid = arm.get("variant_id").toString();
            double alpha = ((Number) arm.get("alpha")).doubleValue();
            double beta  = ((Number) arm.get("beta")).doubleValue();
            double prob  = alpha / Math.max(alpha + beta, 1e-9);
            currentPolicyProbs.put(vid, prob);
        }

        // Call ml-service OPE endpoint
        String url = appProperties.getMlServiceUrl() + "/ope/evaluate";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        Map<String, Object> body = new HashMap<>();
        body.put("decisions", decisions);
        body.put("current_policy_probs", currentPolicyProbs);
        body.put("min_ess", 200.0);

        @SuppressWarnings("unchecked")
        Map<String, Object> opeResult;
        try {
            opeResult = restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception e) {
            log.error("OPE ml-service call failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ML service unavailable");
        }

        if (opeResult == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Empty OPE response");
        }

        // Save to ope_runs
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ope_runs
                (id, tenant_id, policy_version, ips, snips, dr, ci_low, ci_high,
                 ess, clipped_mass, verdict, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                runId, tenantId,
                opeResult.getOrDefault("policy_version", "").toString(),
                toFloat(opeResult.get("ips")),
                toFloat(opeResult.get("snips")),
                toFloat(opeResult.get("dr")),
                toFloat(opeResult.get("ci_low")),
                toFloat(opeResult.get("ci_high")),
                toFloat(opeResult.get("ess")),
                toFloat(opeResult.get("clipped_mass")),
                opeResult.getOrDefault("verdict", "INSUFFICIENT_DATA").toString());

        Map<String, Object> result = new HashMap<>(opeResult);
        result.put("run_id", runId);
        result.put("n_decisions", decisions.size());
        return result;
    }

    private Float toFloat(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(o.toString()); } catch (Exception e) { return null; }
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
