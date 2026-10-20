package com.marketingai.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.service.AlertService;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.SiteHealthResumeService;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synthetic form health check every 30 minutes (roadmap 3-13; vision §11.4).
 *
 * Mode per tenant ({@code tenants.settings.form_check_mode}): {@code browser} loads the landing
 * in headless Chromium, fills and submits the form (ML /analyze/form-health mode=browser);
 * {@code http} (default) posts to form_endpoint_url. The ML side falls back to HTTP when
 * Playwright is not installed and reports {@code method}.
 *
 * Two consecutive FAILs open a FORM_ERROR incident and pause the form; resume needs three
 * consecutive passes AND a marketer's confirmation (SiteHealthResumeService).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormHealthScheduler {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;
    private final AlertService alertService;
    private final SiteHealthResumeService resumeService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Scheduled(fixedDelay = 1_800_000)
    public void checkFormHealth() {
        List<Map<String, Object>> landings;
        try {
            landings = jdbc.queryForList("""
                SELECT lv.id, lv.tenant_id, lv.slug, lv.form_endpoint_url,
                       lv.consecutive_form_passes, lv.form_paused,
                       t.settings->>'base_url'                  AS base_url,
                       t.settings->>'form_check_mode'           AS mode,
                       t.settings->>'form_check_success_pattern' AS success_pattern,
                       t.settings->'form_check_fields'          AS fields
                FROM landing_variants lv
                JOIN tenants t ON t.id = lv.tenant_id
                WHERE lv.status = 'ACTIVE'
                  AND (lv.form_endpoint_url IS NOT NULL OR t.settings->>'form_check_mode' = 'browser')
                LIMIT 50
                """);
        } catch (Exception e) {
            log.warn("FormHealthScheduler: query failed — {}", e.getMessage());
            return;
        }

        for (Map<String, Object> landing : landings) {
            UUID variantId = (UUID) landing.get("id");
            UUID tenantId = (UUID) landing.get("tenant_id");
            try {
                Map<String, Object> result = check(landing);
                boolean ok = Boolean.TRUE.equals(result.get("ok"));
                String method = String.valueOf(result.getOrDefault("method", "http"));
                if (result.get("ok") == null) {           // browser unavailable and no HTTP endpoint → nothing to judge
                    log.info("FormHealthScheduler: no check possible for landing {} ({})", variantId, result.get("error"));
                    continue;
                }
                recordCheck(tenantId, variantId, ok, method, result);

                int currentPasses = landing.get("consecutive_form_passes") instanceof Number n ? n.intValue() : 0;
                if (ok) {
                    jdbc.update("UPDATE landing_variants SET consecutive_form_passes = ? WHERE id = ?", currentPasses + 1, variantId);
                } else {
                    jdbc.update("UPDATE landing_variants SET consecutive_form_passes = 0 WHERE id = ?", variantId);
                    log.warn("FormHealthScheduler: form check FAILED for landing {} [{}]: {}", variantId, method, result.get("error"));
                    openIncidentIfRepeated(tenantId, variantId, landing);
                }
                // resume protocol: counts consecutive passes on the open incident, asks for confirmation, resumes
                resumeService.recordCheck(tenantId, variantId, ok);
            } catch (Exception e) {
                log.warn("FormHealthScheduler: error checking landing {}: {}", variantId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(Map<String, Object> landing) {
        Map<String, Object> req = new HashMap<>();
        String mode = "browser".equalsIgnoreCase(String.valueOf(landing.get("mode"))) ? "browser" : "http";
        String baseUrl = (String) landing.get("base_url");
        req.put("mode", mode);
        req.put("form_endpoint_url", landing.get("form_endpoint_url"));
        if (baseUrl != null && !baseUrl.isBlank()) {
            req.put("page_url", baseUrl.replaceAll("/$", "") + "/l/" + landing.get("slug"));
        }
        if (landing.get("success_pattern") != null) req.put("success_pattern", landing.get("success_pattern"));
        Map<String, Object> fields = Jsonb.asMap(landing.get("fields"));
        if (!fields.isEmpty()) req.put("fields", fields);
        Map<String, Object> result = mlClient.post("/analyze/form-health", req, Map.class);
        return result != null ? result : Map.of("ok", false, "method", mode, "error", "ml-service returned null");
    }

    private void recordCheck(UUID tenantId, UUID variantId, boolean ok, String method, Map<String, Object> result) {
        try {
            Map<String, Object> detail = new HashMap<>();
            detail.put("error", result.get("error"));
            detail.put("final_url", result.get("final_url"));
            detail.put("post_statuses", result.get("post_statuses"));
            jdbc.update("""
                    INSERT INTO site_health_checks (tenant_id, variant_id, check_type, status, latency_ms, http_status, method, detail)
                    VALUES (?, ?, 'SYNTHETIC_FORM', ?, ?, ?, ?, ?::jsonb)
                    """, tenantId, variantId, ok ? "PASS" : "FAIL",
                    result.get("latency_ms") instanceof Number n ? n.intValue() : null,
                    result.get("status") instanceof Number s ? s.intValue() : null,
                    method, MAPPER.writeValueAsString(detail));
        } catch (Exception e) {
            log.debug("site_health_checks insert failed: {}", e.getMessage());
        }
    }

    /** Two consecutive FORM fails within 2 h → FORM_ERROR incident + form paused (no ad-set pause: the page itself is up). */
    private void openIncidentIfRepeated(UUID tenantId, UUID variantId, Map<String, Object> landing) {
        Integer fails = jdbc.queryForObject("""
                SELECT COUNT(*) FROM site_health_checks
                WHERE tenant_id = ? AND variant_id = ? AND check_type = 'SYNTHETIC_FORM' AND status = 'FAIL'
                  AND checked_at > now() - interval '2 hours'
                """, Integer.class, tenantId, variantId);
        if (fails == null || fails < 2) return;
        List<Map<String, Object>> open = jdbc.queryForList(
                "SELECT id FROM site_health_incidents WHERE tenant_id = ? AND variant_id = ? AND resolved_at IS NULL",
                tenantId, variantId);
        if (!open.isEmpty()) return;
        jdbc.update("""
                INSERT INTO site_health_incidents (tenant_id, variant_id, cause, consecutive_fails, alerted)
                VALUES (?, ?, 'FORM_ERROR', ?, TRUE)
                """, tenantId, variantId, fails);
        jdbc.update("UPDATE landing_variants SET form_paused = TRUE WHERE id = ?", variantId);
        alertService.sendAlert("🔴 Site Health: form on landing " + landing.get("slug") + " failed " + fails
                + " consecutive synthetic submissions. Form paused; incident opened.");
    }
}
