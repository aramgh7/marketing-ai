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
 * MMF + Offer Explorer (3-19).
 * GET  /api/v1/mmf          — latest diagnoses
 * GET  /api/v1/mmf/offers   — active offer candidates
 * POST /api/v1/mmf/diagnose — trigger ML diagnosis for a campaign
 */
@Slf4j
@RestController
@RequestMapping("/mmf")
@RequiredArgsConstructor
public class MmfController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @GetMapping
    public List<Map<String, Object>> getRecentDiagnoses() {
        UUID tenantId = currentTenant();
        return jdbc.queryForList("""
                SELECT id, audience_key AS campaign_id, verdict AS diagnosis,
                       p_any_above_breakeven AS confidence, signals AS recommendations, created_at
                FROM mmf_diagnoses
                WHERE tenant_id = ?
                  AND created_at > now() - interval '7 days'
                ORDER BY created_at DESC
                LIMIT 20
                """, tenantId);
    }

    @GetMapping("/offers")
    public List<Map<String, Object>> getActiveOffers() {
        UUID tenantId = currentTenant();
        return jdbc.queryForList("""
                SELECT id, audience_key AS campaign_id, hypothesis AS label, sub_atoms,
                       rank_score AS expected_value, status, created_at
                FROM offer_candidates
                WHERE tenant_id = ?
                  AND status = 'ACTIVE'
                ORDER BY expected_value DESC
                LIMIT 50
                """, tenantId);
    }

    record DiagnoseRequest(UUID campaign_id, Map<String, Object> metrics) {}

    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody DiagnoseRequest req) {
        UUID tenantId = currentTenant();

        String url = appProperties.getMlServiceUrl() + "/mmf/diagnose";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    url, new HttpEntity<>(req.metrics(), headers), Map.class);
        } catch (Exception e) {
            log.error("MMF ml-service call failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ML service unavailable");
        }

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Empty MMF response");
        }

        String diagnosis = mlResult.getOrDefault("diagnosis", "HEALTHY").toString();
        double confidence = mlResult.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        Object recommendations = mlResult.getOrDefault("recommendations", List.of());

        UUID diagId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mmf_diagnoses
                (id, tenant_id, audience_key, verdict, p_any_above_breakeven, signals, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, now())
                """,
                diagId, tenantId, String.valueOf(req.campaign_id()),
                diagnosis, (float) confidence,
                recommendations.toString().replace("[", "[\"").replace("]", "\"]").replace(", ", "\", \""));

        Map<String, Object> result = new HashMap<>(mlResult);  // safe: mlResult is Map<String,Object>
        result.put("diagnosis_id", diagId);
        result.put("campaign_id", req.campaign_id());
        return result;
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
