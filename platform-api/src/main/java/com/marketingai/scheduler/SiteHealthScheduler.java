package com.marketingai.scheduler;

import com.marketingai.service.MetaApiClient;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pings every ACTIVE landing variant URL every minute.
 * Two consecutive FAILs within 10 minutes → open incident + Telegram alert.
 * Uses sentinel UA to avoid polluting analytics sessions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiteHealthScheduler {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;
    private final RestTemplate restTemplate;
    private final MetaApiClient metaApiClient;

    public static final String SENTINEL_UA = "MarketingAI-HealthCheck/1.0";

    @Scheduled(fixedDelay = 60_000)
    public void checkAllLandings() {
        List<Map<String, Object>> variants = jdbc.queryForList("""
                SELECT lv.id AS variant_id, lv.tenant_id, lv.slug,
                       t.settings->>'base_url' AS base_url
                FROM landing_variants lv
                JOIN tenants t ON t.id = lv.tenant_id
                WHERE lv.status = 'ACTIVE'
                """);

        for (Map<String, Object> variant : variants) {
            UUID tenantId  = (UUID) variant.get("tenant_id");
            UUID variantId = (UUID) variant.get("variant_id");
            String slug    = (String) variant.get("slug");
            String baseUrl = (String) variant.get("base_url");

            if (baseUrl == null || baseUrl.isBlank()) continue;

            String url = baseUrl.replaceAll("/$", "") + "/l/" + slug;
            checkUrl(tenantId, variantId, url);
        }
    }

    private void checkUrl(UUID tenantId, UUID variantId, String url) {
        long start = System.currentTimeMillis();
        String status = "PASS";
        int httpCode = 0;
        String failCause = null;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", SENTINEL_UA);

            var resp = restTemplate.exchange(url, HttpMethod.HEAD,
                    new HttpEntity<>(headers), String.class);
            httpCode = resp.getStatusCode().value();
            if (httpCode >= 500) {
                status = "FAIL";
                failCause = "HTTP_5XX";
            }
        } catch (ResourceAccessException e) {
            status = "FAIL";
            failCause = "TIMEOUT";
        } catch (Exception e) {
            status = "FAIL";
            failCause = "HTTP_5XX";
        }

        int latency = (int) (System.currentTimeMillis() - start);

        jdbc.update("""
                INSERT INTO site_health_checks (tenant_id, variant_id, check_type, status, latency_ms, http_status)
                VALUES (?, ?, 'HTTP', ?, ?, ?)
                """, tenantId, variantId, status, latency, httpCode);

        if ("FAIL".equals(status)) {
            evaluateIncident(tenantId, variantId, failCause, url);
        }
    }

    private void evaluateIncident(UUID tenantId, UUID variantId, String cause, String url) {
        Integer consecutiveFails = jdbc.queryForObject("""
                SELECT COUNT(*) FROM site_health_checks
                WHERE tenant_id = ? AND variant_id = ? AND status = 'FAIL'
                  AND checked_at > now() - interval '10 minutes'
                """, Integer.class, tenantId, variantId);

        if (consecutiveFails != null && consecutiveFails >= 2) {
            List<Map<String, Object>> openIncidents = jdbc.queryForList("""
                    SELECT id FROM site_health_incidents
                    WHERE tenant_id = ? AND variant_id = ? AND resolved_at IS NULL
                    """, tenantId, variantId);

            if (openIncidents.isEmpty()) {
                jdbc.update("""
                        INSERT INTO site_health_incidents (tenant_id, variant_id, cause, consecutive_fails, alerted)
                        VALUES (?, ?, ?, ?, TRUE)
                        """, tenantId, variantId, cause, consecutiveFails);

                alertService.sendAlert(
                        "🔴 Site Health: " + url + " failed " + consecutiveFails +
                        " consecutive checks (" + cause + "). Incident opened.");
                log.warn("Site health incident opened: tenant={} variant={} cause={}", tenantId, variantId, cause);

                // P0 autopause: pause all ACTIVE ad sets to prevent wasted spend during site outage
                metaApiClient.enqueue("P0", null, tenantId, "AUTOPAUSE_SITE_INCIDENT", () -> {
                    int paused = jdbc.update(
                            "UPDATE meta_ad_sets SET status='PAUSED', updated_at=now() WHERE tenant_id=? AND status='ACTIVE'",
                            tenantId);
                    if (paused > 0) {
                        log.warn("Site health P0 autopause: tenant={} paused {} ad sets", tenantId, paused);
                    }
                });
            }
        }
    }
}
