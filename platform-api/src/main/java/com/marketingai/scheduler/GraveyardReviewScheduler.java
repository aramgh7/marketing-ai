package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Weekly graveyard review — Monday 08:00 UTC (v1.1 — vision §13.2).
 * Checks anchor_divergence_log for MARKET/FRAUD/TECHNICAL verdicts;
 * sets retry_candidate=true on creative_postmortems when conditions are met.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraveyardReviewScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AlertService alertService;

    @Scheduled(cron = "0 0 8 * * MON")
    public void runWeeklyReview() {
        List<UUID> tenantIds = jdbc.queryForList("""
                SELECT DISTINCT tenant_id FROM creative_postmortems
                WHERE retry_candidate = false
                  AND created_at > now() - interval '90 days'
                """, UUID.class);

        log.info("Graveyard review: {} tenants", tenantIds.size());
        for (UUID tenantId : tenantIds) {
            try {
                reviewTenant(tenantId);
            } catch (Exception e) {
                log.warn("Graveyard review failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void reviewTenant(UUID tenantId) {
        List<Map<String, Object>> postmortems = jdbc.queryForList("""
                SELECT cp.id AS postmortem_id, cp.creative_id
                FROM creative_postmortems cp
                WHERE cp.tenant_id = ?
                  AND cp.retry_candidate = false
                  AND cp.created_at > now() - interval '90 days'
                """, tenantId);

        for (Map<String, Object> pm : postmortems) {
            UUID postmortemId = (UUID) pm.get("postmortem_id");
            UUID creativeId   = (UUID) pm.get("creative_id");

            String anchorVerdict = getLatestAnchorVerdict(tenantId, creativeId);
            boolean fraudFlag            = hasFraudFlag(tenantId, creativeId);
            boolean siteHealthIncident   = hasSiteHealthIncident(tenantId, creativeId);

            Boolean shouldRetry = callGraveyardReview(anchorVerdict, fraudFlag, siteHealthIncident);
            if (Boolean.TRUE.equals(shouldRetry)) {
                String reason = buildReason(anchorVerdict, fraudFlag, siteHealthIncident);
                jdbc.update("""
                        UPDATE creative_postmortems
                        SET retry_candidate  = true,
                            retry_reason     = ?,
                            retry_flagged_at = now()
                        WHERE id = ?
                        """, reason, postmortemId);
                log.info("Graveyard retry flagged for creative {} (reason: {})", creativeId, reason);
            }
        }
    }

    private String getLatestAnchorVerdict(UUID tenantId, UUID creativeId) {
        try {
            List<String> verdicts = jdbc.queryForList("""
                    SELECT verdict FROM anchor_divergence_log
                    WHERE tenant_id = ? AND creative_id = ?
                    ORDER BY created_at DESC LIMIT 1
                    """, String.class, tenantId, creativeId);
            return verdicts.isEmpty() ? null : verdicts.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasFraudFlag(UUID tenantId, UUID creativeId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM fraud_share_alerts
                    WHERE tenant_id = ? AND created_at > now() - interval '30 days'
                    """, Integer.class, tenantId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasSiteHealthIncident(UUID tenantId, UUID creativeId) {
        // MVP: no site health table yet; always false until Phase 7
        return false;
    }

    @SuppressWarnings("unchecked")
    private Boolean callGraveyardReview(String anchorVerdict, boolean fraudFlag, boolean siteHealthIncident) {
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            if (anchorVerdict != null) req.put("anchor_verdict", anchorVerdict);
            req.put("fraud_flag",            fraudFlag);
            req.put("site_health_incident",  siteHealthIncident);
            Map<String, Object> resp = restTemplate.postForObject(
                    "http://ml-service:8001/evolve/graveyard-review", req, Map.class);
            return resp != null && Boolean.TRUE.equals(resp.get("retry_candidate"));
        } catch (Exception e) {
            log.debug("Graveyard review call failed: {}", e.getMessage());
            return false;
        }
    }

    private String buildReason(String anchorVerdict, boolean fraudFlag, boolean siteHealthIncident) {
        if ("MARKET".equals(anchorVerdict)) return "MARKET_SHIFT";
        if ("FRAUD".equals(anchorVerdict) || fraudFlag) return "FRAUD_CLEARED";
        if ("TECHNICAL".equals(anchorVerdict) || siteHealthIncident) return "TECHNICAL_RESOLVED";
        return "REVIEW";
    }
}
