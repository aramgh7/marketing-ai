package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reactive Competitive Intelligence (vision §9.1).
 *
 * Pipeline per tenant:
 *   1. Read competitor_page_ids from tenants.settings JSONB
 *   2. Poll Meta Ad Library API for ads running > 14 days on those pages
 *   3. For each new/updated ad: call ml-service /competitive/extract-atoms (Claude LLM)
 *   4. Group into an alert with counter-hypotheses
 *   5. Notify via Telegram — marketer approves in admin panel
 *
 * ⚠️ Auto-deploy WITHOUT human approval is FORBIDDEN (vision §9.1).
 *    Approved alerts are surfaced to the test-batch workflow only after
 *    POST /competitive-intel/{id}/approve by an authenticated user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompetitiveIntelligenceService {

    private static final int    MIN_RUN_DAYS     = 14;
    private static final String META_API_VERSION = "v19.0";
    private static final String META_AD_ARCHIVE  = "https://graph.facebook.com/%s/ads_archive";

    private final JdbcTemplate               jdbc;
    private final RestTemplate               restTemplate;
    private final AppProperties              appProperties;
    private final AlertService alertService;
    private static final ObjectMapper        MAPPER = new ObjectMapper();

    public void runWeeklyScan() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id::text AS id, settings, meta_access_token_enc FROM tenants");
        for (Map<String, Object> row : tenants) {
            String tenantId = (String) row.get("id");
            try {
                scanTenant(tenantId, (String) row.get("settings"));
            } catch (Exception e) {
                log.error("Competitive intel scan failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    private void scanTenant(String tenantId, String settingsJson) throws Exception {
        List<String> pageIds = extractCompetitorPageIds(settingsJson);
        if (pageIds.isEmpty()) {
            log.debug("No competitor_page_ids configured for tenant {}", tenantId);
            return;
        }

        String accessToken = getMetaAccessToken(tenantId);
        if (accessToken == null || accessToken.isBlank()) {
            log.debug("No Meta access token for tenant {} — skipping CI scan", tenantId);
            return;
        }

        List<Map<String, Object>> longRunningAds = fetchAdLibrary(pageIds, accessToken);
        if (longRunningAds.isEmpty()) return;

        List<String> newAdIds = new ArrayList<>();
        List<String> pageNames = new ArrayList<>();

        for (Map<String, Object> ad : longRunningAds) {
            String metaAdId  = str(ad.get("id"));
            String pageId    = str(ad.get("page_id"));
            String pageName  = str(ad.get("page_name"));
            String adTitle   = str(ad.get("ad_creative_link_title"));
            String adBody    = str(ad.get("ad_creative_body"));
            String snapUrl   = str(ad.get("ad_snapshot_url"));
            int    runDays   = computeRunDays(str(ad.get("ad_delivery_start_time")));

            if (runDays < MIN_RUN_DAYS) continue;

            // Extract atoms via ml-service LLM
            Map<String, Object> atomResult = extractAtoms(tenantId, adTitle, adBody, pageName, runDays);
            String atomsJson = MAPPER.writeValueAsString(atomResult.getOrDefault("atoms", Map.of()));
            String countersJson = MAPPER.writeValueAsString(atomResult.getOrDefault("counter_hypotheses", List.of()));

            // Upsert into competitor_ads
            Integer updated = jdbc.queryForObject(
                    """
                    INSERT INTO competitor_ads
                        (tenant_id, meta_ad_id, page_id, page_name, ad_title, ad_body,
                         snapshot_url, running_since, run_days, atoms_extracted, last_seen_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, ?, CURRENT_DATE - ?::int, ?, ?::jsonb, now())
                    ON CONFLICT (tenant_id, meta_ad_id) DO UPDATE SET
                        run_days       = EXCLUDED.run_days,
                        atoms_extracted = EXCLUDED.atoms_extracted,
                        last_seen_at   = now()
                    RETURNING (xmax = 0)::int AS is_new
                    """,
                    Integer.class,
                    tenantId, metaAdId, pageId, pageName, adTitle, adBody,
                    snapUrl, runDays, runDays, atomsJson
            );

            if (Boolean.TRUE.equals(updated != null && updated == 1)) {
                newAdIds.add(uuid(tenantId, metaAdId));
                if (pageName != null && !pageNames.contains(pageName)) pageNames.add(pageName);

                // Create per-ad alert with counter-hypotheses
                createAlert(tenantId, metaAdId, countersJson);

                // Block F §4: insert COMPETITOR signal into external_signals
                // so diagnostics and GLM can treat competitor activity as a covariate
                insertCompetitorSignal(tenantId, metaAdId, pageName, runDays, atomsJson);
            }
        }

        if (!newAdIds.isEmpty()) {
            alertService.sendAlert(String.format(
                    "🕵️ Competitive Intelligence Alert — Tenant: %s\n\nDetected %d competitor ad(s) running >14 days from: %s\n\nCounter-hypotheses generated. Login to approve the test batch.",
                    tenantId, newAdIds.size(), String.join(", ", pageNames)));
            log.info("Competitive intel: {} new ads for tenant {}", newAdIds.size(), tenantId);
        }
    }

    // ─── Meta Ad Library API ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchAdLibrary(List<String> pageIds, String accessToken) {
        String minDate = LocalDate.now().minusDays(MIN_RUN_DAYS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE);

        String url = UriComponentsBuilder
                .fromHttpUrl(String.format(META_AD_ARCHIVE, META_API_VERSION))
                .queryParam("ad_active_status",       "ACTIVE")
                .queryParam("ad_delivery_date_min",   minDate)
                .queryParam("search_page_ids",        String.join(",", pageIds))
                .queryParam("fields",
                        "id,ad_creative_body,ad_creative_link_title,ad_snapshot_url," +
                        "page_name,page_id,ad_delivery_start_time")
                .queryParam("access_token", accessToken)
                .queryParam("limit", "50")
                .toUriString();

        try {
            @SuppressWarnings("rawtypes")
            Map response = restTemplate.getForObject(url, Map.class);
            if (response == null) return List.of();
            Object data = response.get("data");
            return data instanceof List ? (List<Map<String, Object>>) data : List.of();
        } catch (Exception e) {
            log.error("Ad Library API call failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ─── ml-service LLM atom extraction ──────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractAtoms(String tenantId, String title, String body,
                                              String pageName, int runDays) {
        String url = appProperties.getMlServiceUrl() + "/competitive/extract-atoms";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        Map<String, Object> reqBody = new HashMap<>();
        reqBody.put("ad_title",  title);
        reqBody.put("ad_body",   body);
        reqBody.put("page_name", pageName);
        reqBody.put("run_days",  runDays);

        try {
            Map<?, ?> result = restTemplate.postForObject(
                    url, new HttpEntity<>(reqBody, headers), Map.class);
            return result != null ? (Map<String, Object>) result : Map.of();
        } catch (Exception e) {
            log.warn("Atom extraction failed for ad (title={}): {}", title, e.getMessage());
            return Map.of();
        }
    }

    private void createAlert(String tenantId, String metaAdId, String countersJson) {
        try {
            jdbc.update(
                    """
                    INSERT INTO competitive_intel_alerts
                        (tenant_id, competitor_ad_ids, counter_hypotheses)
                    SELECT ?::uuid,
                           ARRAY[ca.id],
                           ?::jsonb
                    FROM   competitor_ads ca
                    WHERE  ca.tenant_id  = ?::uuid
                      AND  ca.meta_ad_id = ?
                    """,
                    tenantId, countersJson, tenantId, metaAdId
            );
        } catch (Exception e) {
            log.error("Failed to create CI alert: {}", e.getMessage());
        }
    }

    /**
     * Block F §4: Write COMPETITOR signal into external_signals so diagnostics and
     * GLM models can treat competitor ad activity as a covariate (vision §9.1).
     * signal_date = today; value = run_days (proxy for competitive pressure intensity).
     */
    private void insertCompetitorSignal(String tenantId, String metaAdId, String pageName,
                                        int runDays, String atomsJson) {
        try {
            String description = String.format("Competitor ad (page: %s, meta_ad_id: %s) running %d days. Atoms: %s",
                    pageName, metaAdId, runDays, atomsJson);
            jdbc.update(
                """
                INSERT INTO external_signals
                    (tenant_id, signal_type, signal_date, value, description)
                VALUES (?::uuid, 'COMPETITOR', CURRENT_DATE, ?, ?)
                ON CONFLICT (tenant_id, signal_type, signal_date)
                DO UPDATE SET value = GREATEST(external_signals.value, EXCLUDED.value),
                              description = EXCLUDED.description
                """,
                tenantId, (double) runDays, description);
        } catch (Exception e) {
            log.warn("Failed to insert COMPETITOR external signal: {}", e.getMessage());
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> extractCompetitorPageIds(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) return List.of();
        try {
            JsonNode root = MAPPER.readTree(settingsJson);
            JsonNode ids = root.get("competitor_page_ids");
            if (ids == null || !ids.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            ids.forEach(n -> result.add(n.asText()));
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String getMetaAccessToken(String tenantId) {
        try {
            // meta_access_token_enc is stored as bytea — for now use plaintext fallback
            return jdbc.queryForObject(
                    "SELECT ENCODE(meta_access_token_enc, 'escape') FROM tenants WHERE id = ?::uuid",
                    String.class, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private static int computeRunDays(String startDate) {
        if (startDate == null || startDate.isBlank()) return 0;
        try {
            LocalDate start = LocalDate.parse(startDate.substring(0, 10));
            return (int) java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(Object v) { return v == null ? null : v.toString(); }

    private static String uuid(String tenantId, String metaAdId) {
        return tenantId + ":" + metaAdId;
    }
}
