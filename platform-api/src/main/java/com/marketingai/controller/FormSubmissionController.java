package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.service.FunnelService;
import com.marketingai.service.ValueFormulaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/form")
@RequiredArgsConstructor
@Slf4j
public class FormSubmissionController {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final RestTemplate      restTemplate;
    private final AppProperties appProperties;
    private final ValueFormulaService valueFormulaService;
    private final FunnelService     funnelService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        UUID sessionId = body.get("session_id") != null
            ? UUID.fromString((String) body.get("session_id")) : null;

        Boolean selfAccredited = body.get("self_accredited") instanceof Boolean b ? b : null;
        String  ticketBucket   = (String) body.get("ticket_bucket");

        if (sessionId != null) {
            jdbc.update("""
                UPDATE sessions SET
                    self_accredited = ?,
                    ticket_bucket   = ?,
                    funnel_key      = COALESCE(funnel_key, 'investor')
                WHERE id = ? AND tenant_id = ?
                """, selfAccredited, ticketBucket, sessionId, tenantId);
        }

        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO form_submissions
                (id, tenant_id, session_id, funnel_key, self_accredited, ticket_bucket,
                 first_name, last_name, email, phone, company, utm_source, utm_campaign,
                 raw_payload, consent_level)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
            submissionId, tenantId, sessionId,
            body.getOrDefault("funnel_key", "investor"),
            selfAccredited, ticketBucket,
            body.get("first_name"), body.get("last_name"),
            body.get("email"), body.get("phone"), body.get("company"),
            body.get("utm_source"), body.get("utm_campaign"),
            toJson(body), body.getOrDefault("consent_level", "basic"));

        if (selfAccredited != null && ticketBucket != null) {
            // Look up value_formula for FORM_SUBMIT stage from capi_event_map
            String formula = lookupFormula(tenantId, "FORM_SUBMIT");
            double predictedValue = formula != null
                ? valueFormulaService.computeValue(tenantId, formula, selfAccredited, ticketBucket)
                : 0.0;
            if (predictedValue > 0) {
                jdbc.update("""
                    INSERT INTO value_calibration_log
                        (tenant_id, session_id, predicted_value, ticket_bucket, self_accredited, stage_key)
                    VALUES (?, ?, ?, ?, ?, 'FORM_SUBMIT')
                    """, tenantId, sessionId, predictedValue, ticketBucket, selfAccredited);
            }
        }

        log.debug("Form submitted: submission={} tenant={}", submissionId, tenantId);
        return ResponseEntity.ok(Map.of("submission_id", submissionId, "status", "accepted"));
    }

    @GetMapping("/calibration-report")
    public ResponseEntity<Map<String, Object>> calibrationReport(
            @RequestAttribute("tenantId") UUID tenantId) {
        List<Map<String, Object>> log = jdbc.queryForList("""
            SELECT predicted_value, actual_value, ticket_bucket, self_accredited
            FROM value_calibration_log WHERE tenant_id = ?
            ORDER BY logged_at DESC LIMIT 500
            """, tenantId);
        Map<String, Object> req = Map.of("calibration_log", log, "min_samples", 20);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/decide/form/calibrate-value", req, Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage()));
        }
    }

    private String lookupFormula(java.util.UUID tenantId, String stageKey) {
        try {
            return jdbc.queryForObject(
                "SELECT value_formula FROM capi_event_map WHERE tenant_id = ? AND stage_key = ? AND is_active = TRUE LIMIT 1",
                String.class, tenantId, stageKey);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }
}
