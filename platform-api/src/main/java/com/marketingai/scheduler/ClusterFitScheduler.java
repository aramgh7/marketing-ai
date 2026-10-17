package com.marketingai.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Weekly K-means cluster fitting (Phase 5-1, §7.3 Этап 1).
 *
 * Fires Sunday 03:00 UTC. For each tenant:
 *   1. Pulls sessions with consent_level='full' AND is_suspicious=false (§12.1a, §12.6.3)
 *   2. Sends feature batch to FastAPI /cluster/fit
 *   3. Persists returned centroids + rule labels to behavior_clusters
 *
 * Minimum 50 qualifying sessions required; skips tenant otherwise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClusterFitScheduler {

    private final RestTemplate  restTemplate;
    private final AppProperties appProperties;
    private final JdbcTemplate  jdbc;

    private static final int MIN_SESSIONS = 50;
    private static final int N_CLUSTERS   = 7;

    @Scheduled(cron = "0 0 3 * * SUN", zone = "UTC")
    public void fitAllTenants() {
        log.info("ClusterFitScheduler started");
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants WHERE active = true");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            try {
                fitTenant(tenantId);
            } catch (Exception e) {
                log.error("Cluster fit failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        log.info("ClusterFitScheduler completed");
    }

    private void fitTenant(UUID tenantId) {
        // Pull qualified sessions with their events (last 30 days)
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id AS session_id, s.device, s.started_at, s.last_event_at,
                       s.crm_user_id, s.converted
                FROM sessions s
                WHERE s.tenant_id = ?
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                  AND s.started_at > now() - interval '30 days'
                """, tenantId);

        if (sessions.size() < MIN_SESSIONS) {
            log.debug("Tenant {} has only {} qualifying sessions (need {}), skipping",
                    tenantId, sessions.size(), MIN_SESSIONS);
            return;
        }

        // Build session records with events for ML service
        List<Map<String, Object>> sessionRecords = new ArrayList<>();
        for (Map<String, Object> sess : sessions) {
            String sessionId = (String) sess.get("session_id");
            List<Map<String, Object>> events = jdbc.queryForList("""
                    SELECT event_type, event_data, event_time, block_event_id
                    FROM events
                    WHERE session_id = ? AND tenant_id = ?
                    ORDER BY event_time
                    """, sessionId, tenantId);

            Map<String, Object> record = new HashMap<>();
            record.put("session", sess);
            record.put("events",  events);
            sessionRecords.add(record);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("tenant_id",  tenantId.toString());
        body.put("sessions",   sessionRecords);
        body.put("n_clusters", N_CLUSTERS);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        String url = appProperties.getMlServiceUrl() + "/cluster/fit";

        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                url, new HttpEntity<>(body, headers), Map.class);

        if (result == null) {
            log.error("ML service returned null for tenant {}", tenantId);
            return;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> centroids = (List<Map<String, Object>>) result.get("centroids");
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) result.get("cluster_labels");

        if (centroids == null || labels == null) {
            log.error("Missing centroids or labels in ML response for tenant {}", tenantId);
            return;
        }

        persistClusters(tenantId, centroids, labels);
        log.info("Cluster fit complete for tenant {}: {} clusters from {} sessions",
                tenantId, centroids.size(), sessions.size());
    }

    private void persistClusters(UUID tenantId, List<Map<String, Object>> centroids,
                                  List<String> labels) {
        for (int i = 0; i < centroids.size(); i++) {
            Map<String, Object> c = centroids.get(i);
            String label = i < labels.size() ? labels.get(i) : "researcher";
            int sessionCount = ((Number) c.getOrDefault("_session_count", 0)).intValue();

            // Convert centroid to JSON string for storage
            String centroidJson = mapToJson(c);

            jdbc.update("""
                    INSERT INTO behavior_clusters
                        (tenant_id, cluster_idx, label, session_count, centroid, fitted_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, now())
                    ON CONFLICT (tenant_id, cluster_idx)
                    DO UPDATE SET
                        label = EXCLUDED.label,
                        session_count = EXCLUDED.session_count,
                        centroid = EXCLUDED.centroid,
                        fitted_at = now()
                    """, tenantId, i, label, sessionCount, centroidJson);
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private String mapToJson(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise centroid map to JSON", e);
        }
    }
}
