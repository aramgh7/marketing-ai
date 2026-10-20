package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * No-show Propensity scoring (vision §19.18.2; arch §16.15).
 * Called on BOOKED event. Score drives follow-up audience override.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NoshowPropensityService {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public void scoreOnBooked(UUID tenantId, UUID appointmentId, UUID sessionId) {
        Map<String, Object> features = loadFeatures(tenantId, sessionId, appointmentId);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/decide/propensity/noshow", features, Map.class);
            if (result == null) return;

            String riskTier   = (String) result.get("risk_tier");
            double noshowProb = ((Number) result.get("noshow_prob")).doubleValue();

            jdbc.update("""
                INSERT INTO noshow_scores
                    (tenant_id, session_id, appointment_id, noshow_prob, risk_tier,
                     features, model_version)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, sessionId, appointmentId, noshowProb, riskTier,
                toJson(features), result.getOrDefault("model_version", "rule_based_v1"));

            log.debug("NoShow score: appointment={} risk={} prob={}", appointmentId, riskTier, noshowProb);

            @SuppressWarnings("unchecked")
            Map<String, Object> override = (Map<String, Object>) result.get("followup_override");
            if (override != null && override.get("ghl_tag") != null) {
                log.info("High-risk no-show: appointment={} ghl_tag={}", appointmentId, override.get("ghl_tag"));
            }
        } catch (Exception e) {
            log.warn("NoShow scoring failed for appointment {}: {}", appointmentId, e.getMessage());
        }
    }

    private Map<String, Object> loadFeatures(UUID tenantId, UUID sessionId, UUID appointmentId) {
        try {
            return jdbc.queryForMap("""
                SELECT
                    a.booking_distance_h,
                    (a.lead_tz IS NOT NULL)             AS lead_tz_known,
                    s.ticket_bucket,
                    s.self_accredited,
                    s.device AS device_type,
                    COALESCE(s.geo NOT IN ('US', 'USA', 'United States'), false)  AS is_international
                FROM sessions s
                JOIN appointments a ON a.id = ?
                WHERE s.id = ? AND s.tenant_id = ?
                """, appointmentId, sessionId, tenantId);
        } catch (Exception e) {
            return Map.of("lead_tz_known", false, "is_international", false);
        }
    }

    private String toJson(Object o) {
        try { return new ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }
}
