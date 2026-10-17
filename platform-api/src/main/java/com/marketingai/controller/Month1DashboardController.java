package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Month-1 analytics dashboard.
 * Returns Quality Gate savings and date-to-significance estimates.
 */
@RestController
@RequestMapping("/api/v1/analytics/month1")
@RequiredArgsConstructor
public class Month1DashboardController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public ResponseEntity<Map<String, Object>> month1() {
        UUID tenantId = UUID.fromString(TenantContext.get());

        // Quality Gate savings: sessions/conversions blocked from low-quality variants
        Map<String, Object> qualityGate = jdbc.queryForMap("""
                SELECT
                    COUNT(*) FILTER (WHERE quality_score < 0.3)            AS low_quality_sessions,
                    COUNT(*) FILTER (WHERE quality_score >= 0.3)           AS passing_sessions,
                    ROUND(AVG(quality_score)::numeric, 3)                  AS avg_quality_score,
                    COUNT(*) FILTER (WHERE quality_score < 0.3 AND converted) AS blocked_conversions_estimate
                FROM sessions
                WHERE tenant_id = ?
                  AND quality_score IS NOT NULL
                  AND closed_at >= now() - INTERVAL '30 days'
                  AND consent_level = 'full'
                  AND is_suspicious = false
                """, tenantId);

        // Date-to-significance: active variants with Thompson arms
        List<Map<String, Object>> significance = jdbc.queryForList("""
                SELECT
                    v.id        AS variant_id,
                    v.name      AS variant_name,
                    ta.alpha    AS alpha,
                    ta.beta     AS beta,
                    (ta.alpha + ta.beta - 2) AS total_observations,
                    CASE
                        WHEN (ta.alpha + ta.beta - 2) < 100 THEN 'Collecting data'
                        WHEN ta.alpha / NULLIF(ta.alpha + ta.beta, 0) > 0.55 THEN 'Approaching significance'
                        ELSE 'Insufficient signal'
                    END AS significance_status
                FROM landing_variants v
                JOIN thompson_arms ta ON ta.variant_id = v.id AND ta.context_key = 'global'
                WHERE v.tenant_id = ?
                  AND v.status = 'ACTIVE'
                ORDER BY total_observations DESC
                LIMIT 20
                """, tenantId);

        // Clickbait suspects in last 30 days
        List<Map<String, Object>> clickbaitSuspects = jdbc.queryForList("""
                SELECT creative_id, quality_score, clickbait_suspect, computed_at
                FROM creative_quality
                WHERE tenant_id = ?
                  AND clickbait_suspect = true
                  AND computed_at >= now() - INTERVAL '30 days'
                ORDER BY computed_at DESC
                """, tenantId);

        Map<String, Object> result = new HashMap<>();
        result.put("quality_gate", qualityGate);
        result.put("variant_significance", significance);
        result.put("clickbait_suspects", clickbaitSuspects);
        return ResponseEntity.ok(result);
    }
}
