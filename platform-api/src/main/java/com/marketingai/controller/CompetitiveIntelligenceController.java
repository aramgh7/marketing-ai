package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Competitive Intelligence REST API (vision §9.1).
 *
 * GET  /api/v1/competitive-intel          — list alerts for the authenticated tenant
 * POST /api/v1/competitive-intel/{id}/approve  — human approves a counter-hypothesis batch
 * POST /api/v1/competitive-intel/{id}/dismiss  — marketer dismisses alert (won't re-alert)
 *
 * ⚠️ Auto-deploy without human approval is FORBIDDEN.
 *    approve() only flags the alert; test-batch launch is a separate manual action.
 */
@Slf4j
@RestController
@RequestMapping("/competitive-intel")
@RequiredArgsConstructor
public class CompetitiveIntelligenceController {

    private final JdbcTemplate jdbc;

    @GetMapping
    public List<Map<String, Object>> listAlerts(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "PENDING") String status) {
        return jdbc.queryForList(
                """
                SELECT
                    a.id::text          AS id,
                    a.status,
                    a.counter_hypotheses,
                    a.created_at,
                    a.approved_at,
                    COUNT(ca.id)        AS ad_count,
                    ARRAY_AGG(DISTINCT ca.page_name) AS page_names
                FROM   competitive_intel_alerts a
                JOIN   competitor_ads ca
                    ON  ca.id = ANY(a.competitor_ad_ids)
                    AND ca.tenant_id = a.tenant_id
                WHERE  a.tenant_id = ?
                  AND  a.status    = ?
                GROUP  BY a.id, a.status, a.counter_hypotheses, a.created_at, a.approved_at
                ORDER  BY a.created_at DESC
                LIMIT  50
                """,
                tenantId, status
        );
    }

    @GetMapping("/{alertId}/ads")
    public List<Map<String, Object>> getAlertAds(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID alertId) {
        return jdbc.queryForList(
                """
                SELECT
                    ca.id::text     AS id,
                    ca.meta_ad_id,
                    ca.page_name,
                    ca.ad_title,
                    ca.ad_body,
                    ca.snapshot_url,
                    ca.run_days,
                    ca.atoms_extracted
                FROM   competitor_ads ca
                JOIN   competitive_intel_alerts a
                    ON  ca.id = ANY(a.competitor_ad_ids)
                    AND a.tenant_id = ca.tenant_id
                WHERE  a.id        = ?
                  AND  a.tenant_id = ?
                """,
                alertId, tenantId
        );
    }

    /**
     * Human approval — marks alert APPROVED.
     * ⚠️ This does NOT auto-launch a test batch. The marketer must manually
     * create a campaign using the approved counter-hypotheses.
     */
    @PostMapping("/{alertId}/approve")
    public Map<String, Object> approve(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestAttribute("userId")   UUID userId,
            @PathVariable UUID alertId) {

        int updated = jdbc.update(
                """
                UPDATE competitive_intel_alerts
                SET status = 'APPROVED', approved_by = ?, approved_at = now()
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING'
                """,
                userId, alertId, tenantId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Alert not found, already actioned, or not owned by tenant");
        }
        log.info("CI alert {} approved by user {} (tenant {})", alertId, userId, tenantId);
        return Map.of(
                "id", alertId,
                "status", "APPROVED",
                "message", "Counter-hypotheses approved for review. Launch the test batch manually."
        );
    }

    @PostMapping("/{alertId}/dismiss")
    public Map<String, Object> dismiss(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID alertId) {

        int updated = jdbc.update(
                """
                UPDATE competitive_intel_alerts
                SET status = 'DISMISSED'
                WHERE id = ? AND tenant_id = ? AND status = 'PENDING'
                """,
                alertId, tenantId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Alert not found, already actioned, or not owned by tenant");
        }
        return Map.of("id", alertId, "status", "DISMISSED");
    }
}
