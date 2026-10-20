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
 * Predicted Lead Value at Day 0 (vision §19.18.3; arch §16.16).
 * Called after noshow scoring. Persists prediction for calibration tracking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadValueService {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    public void predictOnBooked(UUID tenantId, UUID appointmentId, UUID sessionId) {
        List<Map<String, Object>> noshowRows = jdbc.queryForList("""
            SELECT noshow_prob FROM noshow_scores
            WHERE appointment_id = ? AND tenant_id = ?
            ORDER BY scored_at DESC LIMIT 1
            """, appointmentId, tenantId);

        double pShow = noshowRows.isEmpty()
            ? 0.70
            : 1.0 - ((Number) noshowRows.get(0).get("noshow_prob")).doubleValue();

        List<String> buckets = jdbc.queryForList(
            "SELECT ticket_bucket FROM sessions WHERE id = ?", String.class, sessionId);
        String ticketBucket = buckets.isEmpty() ? null : buckets.get(0);

        Map<String, Object> req = new HashMap<>();
        req.put("p_show",        pShow);
        req.put("ticket_bucket", ticketBucket != null ? ticketBucket : "");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/decide/predict/lead-value", req, Map.class);
            if (result == null) return;

            double predicted = ((Number) result.get("predicted_value")).doubleValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> components = (Map<String, Object>) result.get("components");

            jdbc.update("""
                INSERT INTO lead_value_predictions
                    (tenant_id, session_id, appointment_id, p_show,
                     p_verified_given_show, p_invest_given_verified,
                     e_ticket, predicted_value, model_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, sessionId, appointmentId, pShow,
                components.getOrDefault("p_verified_given_show", 0.65),
                components.getOrDefault("p_invest_given_verified", 0.21),
                components.getOrDefault("e_ticket", 75000.0),
                predicted,
                result.getOrDefault("model_version", "seed_v1"));

            log.debug("Lead value predicted: appointment={} value={}", appointmentId, predicted);
        } catch (Exception e) {
            log.warn("Lead value prediction failed for {}: {}", appointmentId, e.getMessage());
        }
    }

    public void recordRealised(UUID tenantId, UUID sessionId, double realisedValue) {
        jdbc.update("""
            UPDATE lead_value_predictions
            SET realised_value = ?, realised_at = NOW()
            WHERE session_id = ? AND tenant_id = ? AND realised_value IS NULL
            """, realisedValue, sessionId, tenantId);
    }

    private String toJson(Object o) {
        try { return new ObjectMapper().writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }
}
