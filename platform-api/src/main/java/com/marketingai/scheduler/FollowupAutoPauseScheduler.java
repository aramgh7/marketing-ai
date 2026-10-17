package com.marketingai.scheduler;

import com.marketingai.service.MlServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Hourly auto-pause check for follow-up variants (vision §19.6).
 * Calls ML service to compute P(worse than control) via Monte Carlo.
 * Auto-pauses if P(worse) > 0.95 AND ESS >= 100.
 *
 * SECURITY: Control arm NEVER paused (is_control = true → skip).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupAutoPauseScheduler {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;

    @Scheduled(fixedDelay = 3_600_000) // hourly
    public void checkAutoPause() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM followup_variants WHERE lifecycle_state = 'APPROVED'");

        for (Map<String, Object> tenantRow : tenants) {
            Object tenantId = tenantRow.get("tenant_id");
            try {
                checkTenant(tenantId);
            } catch (Exception e) {
                log.warn("FollowupAutoPause failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void checkTenant(Object tenantId) {
        List<Map<String, Object>> variants = jdbc.queryForList("""
                SELECT id, name, alpha, beta, is_control
                FROM followup_variants
                WHERE tenant_id = ? AND lifecycle_state IN ('APPROVED', 'PAUSED')
                """, tenantId);

        // Find control arm
        Map<String, Object> control = variants.stream()
                .filter(v -> Boolean.TRUE.equals(v.get("is_control")))
                .findFirst()
                .orElse(null);

        if (control == null) return;

        double ctrlAlpha = ((Number) control.get("alpha")).doubleValue();
        double ctrlBeta  = ((Number) control.get("beta")).doubleValue();

        for (Map<String, Object> v : variants) {
            if (Boolean.TRUE.equals(v.get("is_control"))) continue; // never pause control
            if (!"APPROVED".equals(getLifecycleState(tenantId, v.get("id")))) continue;

            double vAlpha = ((Number) v.get("alpha")).doubleValue();
            double vBeta  = ((Number) v.get("beta")).doubleValue();

            Map<String, Object> mlReq = Map.of(
                    "variant_alpha", vAlpha, "variant_beta", vBeta,
                    "control_alpha", ctrlAlpha, "control_beta", ctrlBeta,
                    "min_ess", 100);

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = mlClient.post("/decide/followup/auto-pause", mlReq, Map.class);
                boolean shouldPause = Boolean.TRUE.equals(result.get("should_pause"));

                if (shouldPause) {
                    jdbc.update("""
                            UPDATE followup_variants
                            SET lifecycle_state = 'PAUSED'
                            WHERE id = ?::uuid AND is_control = false
                            """, v.get("id"));
                    log.warn("Follow-up variant AUTO-PAUSED: id={} p_worse={} tenant={}",
                            v.get("id"), result.get("p_worse"), tenantId);
                }
            } catch (Exception e) {
                log.warn("Auto-pause ML call failed for variant={}: {}", v.get("id"), e.getMessage());
            }
        }
    }

    private String getLifecycleState(Object tenantId, Object variantId) {
        try {
            return jdbc.queryForObject(
                    "SELECT lifecycle_state FROM followup_variants WHERE id = ?::uuid AND tenant_id = ?",
                    String.class, variantId, tenantId);
        } catch (Exception e) {
            return "";
        }
    }
}
