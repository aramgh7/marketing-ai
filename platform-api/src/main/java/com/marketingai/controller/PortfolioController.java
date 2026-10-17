package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Portfolio Budget Optimisation endpoint (6-7).
 * Only after >= 6 months of ad-set return history.
 * Output = RECOMMENDATION only — never triggers spend changes.
 * Never inside a CBO campaign.
 * vision §8.5, architecture §15.21.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    /**
     * GET /api/v1/portfolio/recommendation
     * Loads ad-set performance history from channel_insights_history and thompson_arms,
     * then asks FastAPI to compute Sharpe-ratio portfolio weights.
     * Requires total_budget query param.
     */
    @GetMapping("/recommendation")
    public ResponseEntity<Map<String, Object>> recommendation(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "10000") double totalBudget) {

        // Load ad-set daily return series from channel_insights_history (spend vs conversions)
        List<Map<String, Object>> insights = jdbc.queryForList("""
                SELECT campaign_id, channel, date,
                       CASE WHEN spend > 0 THEN (conversions::float / spend) ELSE 0 END AS daily_return
                FROM channel_insights_history
                WHERE tenant_id = ?
                ORDER BY campaign_id, date
                """, tenantId);

        if (insights.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No channel insights history for this tenant. Run ad-set sync first.");
        }

        // Group by campaign_id
        Map<String, List<Double>> returnsByCampaign = new LinkedHashMap<>();
        for (Map<String, Object> row : insights) {
            String campaignId = Objects.toString(row.get("campaign_id"), "unknown");
            double ret = row.get("daily_return") != null
                    ? ((Number) row.get("daily_return")).doubleValue() : 0.0;
            returnsByCampaign.computeIfAbsent(campaignId, k -> new ArrayList<>()).add(ret);
        }

        // Find history date range
        LocalDate earliest = insights.stream()
                .map(r -> {
                    Object d = r.get("date");
                    if (d instanceof java.sql.Date sd) return sd.toLocalDate();
                    return LocalDate.now().minusYears(1);
                })
                .min(Comparator.naturalOrder())
                .orElse(LocalDate.now().minusYears(1));

        LocalDate latest = insights.stream()
                .map(r -> {
                    Object d = r.get("date");
                    if (d instanceof java.sql.Date sd) return sd.toLocalDate();
                    return LocalDate.now();
                })
                .max(Comparator.naturalOrder())
                .orElse(LocalDate.now());

        // Load CBO status from meta_campaigns
        Set<String> cboCampaigns = new HashSet<>(jdbc.queryForList("""
                SELECT id::text FROM meta_campaigns
                WHERE tenant_id = ? AND objective = 'CBO'
                """, String.class, tenantId));

        // Build campaign list for FastAPI
        List<Map<String, Object>> campaigns = returnsByCampaign.entrySet().stream()
                .map(e -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", e.getKey());
                    c.put("name", e.getKey());
                    c.put("is_cbo", cboCampaigns.contains(e.getKey()));
                    c.put("daily_returns", e.getValue());
                    return c;
                })
                .collect(Collectors.toList());

        Map<String, Object> mlReq = new LinkedHashMap<>();
        mlReq.put("campaigns", campaigns);
        mlReq.put("total_budget", totalBudget);
        mlReq.put("earliest_date", earliest.toString());
        mlReq.put("latest_date", latest.toString());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/portfolio/optimize",
                    new HttpEntity<>(mlReq, headers), Map.class);
        } catch (Exception ex) {
            log.warn("Portfolio service failed: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Portfolio service error: " + ex.getMessage());
        }

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Portfolio service returned null");
        }

        Map<String, Object> resp = new LinkedHashMap<>(mlResult);
        resp.put("tenant_id", tenantId);
        resp.put("computed_at", java.time.Instant.now().toString());
        return ResponseEntity.ok(resp);
    }
}
