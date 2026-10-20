package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Campaign-level postmortem (4-x, vision §4.x).
 * Collects metrics, circuit breaker events, and fraud alerts for a closed campaign.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PostmortemController {

    private final JdbcTemplate jdbc;

    @PostMapping("/api/v1/campaigns/{id}/postmortem")
    public ResponseEntity<Map<String, Object>> postmortem(
            @PathVariable UUID id,
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> campaigns = jdbc.queryForList(
                """
                SELECT id, name, status, created_at,
                       monthly_budget_ceiling, target_cpa, breakeven_roas,
                       NULL::real AS bid_cap_amount, NULL::text AS bid_cap_type, contour AS launch_mode
                FROM meta_campaigns
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId);

        if (campaigns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found");
        }

        Map<String, Object> campaign = campaigns.get(0);
        Object campaignCreatedAt = campaign.get("created_at");

        List<Map<String, Object>> cbEvents = jdbc.queryForList(
                """
                SELECT id, level, trigger_reason, bayesian_prob,
                       contour, action_taken, resolved_at, created_at
                FROM circuit_breaker_events
                WHERE tenant_id = ?
                  AND (:created_at::timestamptz IS NULL OR created_at >= :created_at::timestamptz)
                ORDER BY created_at
                """.replace(":created_at::timestamptz", "?"),
                tenantId, campaignCreatedAt, campaignCreatedAt);

        List<Map<String, Object>> fraudAlerts = jdbc.queryForList(
                """
                SELECT id, share_pct AS suspicious_share, NULL::text AS mode, created_at
                FROM fraud_share_alerts
                WHERE tenant_id = ?
                  AND created_at >= ?
                """, tenantId, campaignCreatedAt);

        // Aggregate spend/conversions from channel_insights_history if available
        List<Map<String, Object>> metrics = jdbc.queryForList(
                """
                SELECT channel,
                       SUM(spend)       AS total_spend,
                       SUM(conversions) AS total_conversions,
                       SUM(impressions) AS total_impressions,
                       SUM(clicks)      AS total_clicks
                FROM channel_insights_history
                WHERE tenant_id = ?
                  AND date >= (SELECT created_at::date FROM meta_campaigns WHERE id = ?)
                GROUP BY channel
                """, tenantId, id);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("campaign",      campaign);
        report.put("channel_metrics", metrics);
        report.put("cb_events",     cbEvents);
        report.put("fraud_alerts",  fraudAlerts);
        report.put("generated_at",  Instant.now());

        return ResponseEntity.ok(report);
    }
}
