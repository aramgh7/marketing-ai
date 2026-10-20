package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Off-policy evaluation (3-18, roadmap 9-3): collects the logged decisions with their propensities,
 * calls ml-service {@code POST /ope/evaluate} and stores the run in {@code ope_runs}. Used by
 * {@code OpeController} (on demand) and {@code OpeScheduler} (weekly); {@link #latest(UUID)} feeds the
 * autonomy gate ({@link AutonomyGate}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpeService {

    public static final int LOOKBACK_DAYS = 30;
    public static final int MIN_DECISIONS = 10;

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    /** Latest run as a map (verdict, age_days, …) or empty when the tenant has no run. */
    public Optional<Map<String, Object>> latest(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, policy_version, logged_from, logged_to,
                       ips, snips, dr, ci_low, ci_high, ess, clipped_mass, verdict, created_at
                FROM ope_runs
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, tenantId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        Object created = row.get("created_at");
        if (created instanceof Timestamp ts) {
            row.put("age_days", ChronoUnit.DAYS.between(ts.toInstant(), Instant.now()));
        }
        return Optional.of(row);
    }

    /** Run OPE for a tenant; returns the ml-service result plus run_id, or INSUFFICIENT_DATA without a call. */
    public Map<String, Object> evaluate(UUID tenantId) {
        List<Map<String, Object>> decisions = jdbc.queryForList("""
                SELECT dl.chosen_variant_id AS variant_id,
                       COALESCE((SELECT (c->>'propensity')::float8 FROM jsonb_array_elements(dl.candidates) c
                                 WHERE c->>'variant_id' = dl.chosen_variant_id::text LIMIT 1), 1e-6) AS logged_prob,
                       CASE WHEN EXISTS (SELECT 1 FROM thompson_reward_log r
                                         WHERE r.decision_id = dl.id AND r.field_updated = 'alpha')
                            THEN 1.0 ELSE 0.0 END AS reward
                FROM decision_log dl
                WHERE dl.tenant_id = ?
                  AND dl.decided_at > now() - (? * interval '1 day')
                LIMIT 5000
                """, tenantId, LOOKBACK_DAYS);

        if (decisions.size() < MIN_DECISIONS) {
            return Map.of("verdict", "INSUFFICIENT_DATA",
                    "message", "Need at least " + MIN_DECISIONS + " decision_log rows in the last " + LOOKBACK_DAYS + " days.",
                    "n_decisions", decisions.size());
        }

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
            currentPolicyProbs.put(vid, alpha / Math.max(alpha + beta, 1e-9));
        }

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

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        // Roadmap 9-3: logged_from / logged_to are NOT NULL (V56) and were never written before
        jdbc.update("""
                INSERT INTO ope_runs
                (id, tenant_id, policy_version, logged_from, logged_to, ips, snips, dr, ci_low, ci_high,
                 ess, clipped_mass, verdict, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                runId, tenantId,
                opeResult.getOrDefault("policy_version", "").toString(),
                Timestamp.from(now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS)), Timestamp.from(now),
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

    static Float toFloat(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.floatValue();
        try { return Float.parseFloat(o.toString()); } catch (Exception e) { return null; }
    }
}
