package com.marketingai.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Weekly Niche Creative Entropy Index scan (6-8, arch §15.10).
 * For each niche with >= 5 tenants: computes entropy from hook embeddings,
 * persists to niche_entropy_scores, and alerts via Telegram when diversity_flag=true.
 * Runs every Monday at 07:00 UTC (after TaxonomyRecalibrateScheduler Sun 04:00).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntropyScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final AlertService alertService;
    private final ObjectMapper objectMapper;

    private static final int MIN_TENANTS = 5;

    @Scheduled(cron = "0 0 7 * * MON", zone = "UTC")
    public void runEntropyCheck() {
        log.info("Entropy Index scan started");

        // Load per-niche embedding summary from atom_registry.
        // One representative hook embedding per tenant per niche (latest CONFIRMED entry).
        List<Map<String, Object>> niches = jdbc.queryForList("""
                SELECT niche, COUNT(DISTINCT tenant_count) AS tenant_count
                FROM (
                    SELECT
                        t.settings->>'niche' AS niche,
                        t.id AS tenant_count
                    FROM atom_registry ar
                    JOIN creative_atoms ca ON ca.registry_id = ar.id
                    JOIN creatives c        ON c.id = ca.creative_id
                    JOIN tenants t          ON t.id = c.tenant_id
                    WHERE ar.status = 'CONFIRMED'
                      AND ar.atom_type = 'HOOK'
                      AND t.settings->>'niche' IS NOT NULL
                    GROUP BY t.settings->>'niche', t.id
                ) grouped
                GROUP BY niche
                HAVING COUNT(DISTINCT tenant_count) >= ?
                """, MIN_TENANTS);

        if (niches.isEmpty()) {
            log.info("No niche has >= {} tenants yet — entropy scan skipped", MIN_TENANTS);
            return;
        }

        for (Map<String, Object> nicheRow : niches) {
            String niche = (String) nicheRow.get("niche");
            int tenantCount = ((Number) nicheRow.get("tenant_count")).intValue();

            try {
                processNiche(niche, tenantCount);
            } catch (Exception ex) {
                log.error("Entropy scan failed for niche '{}': {}", niche, ex.getMessage());
            }
        }

        log.info("Entropy Index scan complete for {} niches", niches.size());
    }

    private void processNiche(String niche, int tenantCount) {
        // Load one centroid embedding per tenant (average of their CONFIRMED HOOK centroids)
        List<Map<String, Object>> embRows = jdbc.queryForList("""
                SELECT t.id AS tenant_id,
                       ar.centroid::text AS centroid_text
                FROM atom_registry ar
                JOIN creative_atoms ca ON ca.registry_id = ar.id
                JOIN creatives c        ON c.id = ca.creative_id
                JOIN tenants t          ON t.id = c.tenant_id
                WHERE ar.status = 'CONFIRMED'
                  AND ar.atom_type = 'HOOK'
                  AND t.settings->>'niche' = ?
                GROUP BY t.id, ar.id, ar.centroid
                """, niche);

        if (embRows.size() < MIN_TENANTS) {
            log.debug("Niche '{}': only {} tenants with CONFIRMED HOOK centroids — skipping", niche, embRows.size());
            return;
        }

        // Parse embeddings (stored as pg vector string "[x,y,...]" → list of floats)
        List<List<Double>> embeddings = new ArrayList<>();
        for (Map<String, Object> row : embRows) {
            String centroidStr = Objects.toString(row.get("centroid_text"), "");
            List<Double> vec = parsePgVector(centroidStr);
            if (!vec.isEmpty()) {
                embeddings.add(vec);
            }
        }

        if (embeddings.size() < MIN_TENANTS) {
            log.debug("Niche '{}': parsed only {} valid embeddings — skipping", niche, embeddings.size());
            return;
        }

        // Call FastAPI entropy compute
        Map<String, Object> mlReq = new LinkedHashMap<>();
        mlReq.put("niche", niche);
        mlReq.put("embeddings", embeddings);
        mlReq.put("tenant_count", embeddings.size());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> result;
        try {
            result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/entropy/compute",
                    new HttpEntity<>(mlReq, headers), Map.class);
        } catch (Exception ex) {
            log.error("FastAPI entropy/compute failed for niche '{}': {}", niche, ex.getMessage());
            return;
        }
        if (result == null) return;

        double entropy       = result.get("entropy") != null ? ((Number) result.get("entropy")).doubleValue() : 1.0;
        double meanSim       = result.get("mean_similarity") != null ? ((Number) result.get("mean_similarity")).doubleValue() : 0.0;
        boolean diversityFlag = Boolean.TRUE.equals(result.get("diversity_flag"));
        String recommendation = (String) result.getOrDefault("recommendation", "");

        // Persist
        jdbc.update("""
                INSERT INTO niche_entropy_scores
                    (id, niche, tenant_count, entropy, mean_similarity, diversity_flag, recommendation)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), niche, embeddings.size(),
                entropy, meanSim, diversityFlag, recommendation);

        log.info("Niche '{}': entropy={} diversity_flag={}", niche, entropy, diversityFlag);

        // Telegram alert when diversity is flagged
        if (diversityFlag) {
            String msg = String.format(
                    "⚠️ *Niche Creative Entropy Alert*\n" +
                    "Niche: *%s*\n" +
                    "Entropy: %.3f (threshold: 0.3)\n" +
                    "Tenants: %d\n" +
                    "Recommendation: %s",
                    niche, entropy, embeddings.size(), recommendation);
            try {
                alertService.sendAlert(msg);
            } catch (Exception tex) {
                log.warn("Telegram alert failed for entropy: {}", tex.getMessage());
            }
        }
    }

    /** Parse PostgreSQL vector literal "[x,y,z,...]" or "{x,y,z,...}" to List<Double>. */
    private List<Double> parsePgVector(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String cleaned = raw.trim().replaceAll("[\\[\\]{}]", "");
        String[] parts = cleaned.split(",");
        List<Double> vec = new ArrayList<>(parts.length);
        try {
            for (String p : parts) {
                vec.add(Double.parseDouble(p.trim()));
            }
        } catch (NumberFormatException e) {
            return List.of();
        }
        return vec;
    }
}
