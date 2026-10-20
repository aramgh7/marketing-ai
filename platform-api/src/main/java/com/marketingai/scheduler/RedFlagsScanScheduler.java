package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Red flags scanner — early kill for landing pages with P(bounce > threshold) > 0.90.
 * vision §15.12.5: landings only, NOT Meta creatives.
 * Runs every 30 minutes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedFlagsScanScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final AlertService alertService;


    @Scheduled(fixedDelay = 1_800_000)
    public void scan() {
        // Find active landing variants with bounce data in last 24h
        List<Map<String, Object>> variants = jdbc.queryForList("""
                SELECT
                    lv.id          AS variant_id,
                    lv.tenant_id,
                    t.settings->>'niche' AS niche,
                    COUNT(s.id)    AS total_visits,
                    SUM(CASE WHEN (NOT EXISTS (SELECT 1 FROM events ev WHERE ev.session_id = s.id AND ev.tenant_id = s.tenant_id AND ev.event_type IN ('SCROLL','CLICK','HOVER','BLOCK_VIEW','FORM_START','FORM_SUBMIT','VIDEO_PROGRESS','CTA_CLICK','FIRST_CLICK'))) THEN 1 ELSE 0 END) AS bounced
                FROM landing_variants lv
                JOIN tenants t ON lv.tenant_id = t.id
                JOIN sessions s ON s.variant_id = lv.id AND s.tenant_id = lv.tenant_id
                WHERE lv.status = 'ACTIVE'
                  AND s.started_at >= NOW() - INTERVAL '24 hours'
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                GROUP BY lv.id, lv.tenant_id, t.settings->>'niche'
                HAVING COUNT(s.id) >= 50
                """);

        for (Map<String, Object> v : variants) {
            UUID   variantId   = (UUID)   v.get("variant_id");
            UUID   tenantId    = (UUID)   v.get("tenant_id");
            int    totalVisits = ((Number) v.get("total_visits")).intValue();
            int    bounced     = ((Number) v.get("bounced")).intValue();
            String niche       = (String) v.get("niche");

            try {
                checkVariant(variantId, tenantId, bounced, totalVisits, niche);
            } catch (Exception e) {
                log.warn("RedFlagsScan: variant {} check failed: {}", variantId, e.getMessage());
            }
        }
    }

    private void checkVariant(UUID variantId, UUID tenantId, int bounced, int total, String niche) {
        // Load niche benchmark if available
        List<Map<String, Object>> benchmarks = jdbc.queryForList("""
                SELECT effect_size, ci_lower, ci_upper, is_published
                FROM niche_benchmarks
                WHERE niche = ? AND metric = 'BOUNCE_RATE' AND is_published = true
                LIMIT 1
                """, niche != null ? niche : "UNKNOWN");

        Map<String, Object> benchmark = benchmarks.isEmpty() ? null : benchmarks.get(0);

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("landing_id",       variantId.toString());
        reqBody.put("bounced",          bounced);
        reqBody.put("total_visits",     total);
        reqBody.put("niche_benchmark",  benchmark);
        reqBody.put("is_landing",       true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> result;
        try {
            result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/red-flags/check",
                    new HttpEntity<>(reqBody, headers),
                    Map.class);
        } catch (Exception e) {
            log.warn("RedFlags ML call failed for variant {}: {}", variantId, e.getMessage());
            return;
        }

        if (result == null) return;

        boolean shouldKill = Boolean.TRUE.equals(result.get("should_kill"));
        double  pExceed    = result.get("p_exceed") instanceof Number
                ? ((Number) result.get("p_exceed")).doubleValue() : 0.0;
        double  threshold  = result.get("threshold") instanceof Number
                ? ((Number) result.get("threshold")).doubleValue() : 0.75;
        String  label      = (String) result.getOrDefault("label", "heuristic");

        if (shouldKill) {
            jdbc.update("""
                    INSERT INTO red_flag_events
                        (id, tenant_id, variant_id, p_exceed, threshold, bounced, total_visits, label, action_taken)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, 'ALERT')
                    """,
                    tenantId, variantId, pExceed, threshold, bounced, total, label);

            alertService.sendAlert(String.format(
                    "🚩 Red Flag: landing variant %s — P(bounce > %.2f) = %.3f [%s]. Visits: %d",
                    variantId, threshold, pExceed, label, total));

            log.warn("RedFlag fired: variant={} p_exceed={} threshold={} label={}", variantId, pExceed, threshold, label);
        }
    }
}
