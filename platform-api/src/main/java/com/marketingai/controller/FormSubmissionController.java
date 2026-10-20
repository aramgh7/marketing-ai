package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.marketingai.service.FormSubmissionService;
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
@RequestMapping("/form")
@RequiredArgsConstructor
@Slf4j
public class FormSubmissionController {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final RestTemplate      restTemplate;
    private final AppProperties appProperties;
    private final ValueFormulaService valueFormulaService;
    private final FunnelService     funnelService;
    private final FormSubmissionService formSubmissionService;

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(formSubmissionService.submit(tenantId, body));
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
}
