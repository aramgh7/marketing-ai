package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Geo-Lift incrementality endpoints (5-7).
 * Uses client sales data (NOT pixel); credible intervals on all figures (vision §17.3).
 * Delegates synthetic-control computation to FastAPI.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/geo-lift")
@RequiredArgsConstructor
public class GeoLiftController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    // ── POST / — create test ──────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> createTest(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String testName   = (String) body.get("test_name");
        @SuppressWarnings("unchecked")
        List<String> controlGeos = (List<String>) body.get("control_geos");
        @SuppressWarnings("unchecked")
        List<String> testGeos    = (List<String>) body.get("test_geos");
        String startDate  = (String) body.get("start_date");

        if (testName == null || controlGeos == null || testGeos == null || startDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "test_name, control_geos, test_geos, start_date are required");
        }

        // Validate geo split via FastAPI
        Map<String, Object> validateReq = Map.of("control_geos", controlGeos, "test_geos", testGeos);
        @SuppressWarnings("unchecked")
        Map<String, Object> validation = callFastApi("/geo-lift/validate", validateReq);

        if (Boolean.FALSE.equals(validation.get("valid"))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Invalid geo split: " + validation.get("errors"));
        }

        String controlGeosArr = toArray(controlGeos);
        String testGeosArr    = toArray(testGeos);

        UUID testId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO geo_lift_tests
                    (id, tenant_id, test_name, control_geos, test_geos, start_date, status)
                VALUES (?, ?, ?, ?::text[], ?::text[], ?::date, 'DRAFT')
                """,
                testId, tenantId, testName, controlGeosArr, testGeosArr, startDate);

        log.info("Geo-lift test {} created for tenant {}", testId, tenantId);
        return ResponseEntity.ok(Map.of("test_id", testId, "status", "DRAFT"));
    }

    // ── GET / — list tests ────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTests(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, test_name, control_geos, test_geos,
                       start_date, end_date, status, result, created_at
                FROM geo_lift_tests
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 50
                """, tenantId);
        return ResponseEntity.ok(rows);
    }

    // ── POST /{id}/analyze — run synthetic control ────────────────────────────

    @PostMapping("/{id}/analyze")
    public ResponseEntity<Map<String, Object>> analyze(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM geo_lift_tests WHERE id = ? AND tenant_id = ?",
                id, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Geo-lift test not found");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult = callFastApi("/geo-lift/analyze", body);

        // Persist result
        jdbc.update("""
                UPDATE geo_lift_tests
                SET result = ?::jsonb, status = 'COMPLETED', updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """,
                toJson(mlResult), id, tenantId);

        Map<String, Object> resp = new LinkedHashMap<>(mlResult);
        resp.put("test_id", id);
        return ResponseEntity.ok(resp);
    }

    // ── PUT /{id}/complete — manual close ─────────────────────────────────────

    @PutMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeTest(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {

        int updated = jdbc.update("""
                UPDATE geo_lift_tests
                SET status = 'COMPLETED', end_date = now(), updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Geo-lift test not found");
        }
        return ResponseEntity.ok(Map.of("test_id", id, "status", "COMPLETED"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> callFastApi(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            Map<String, Object> result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + path,
                    new HttpEntity<>(body, headers), Map.class);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "FastAPI returned null from " + path);
            }
            return result;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception ex) {
            log.warn("FastAPI {} call failed: {}", path, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "ML service error: " + ex.getMessage());
        }
    }

    private String toArray(List<String> list) {
        return "{" + String.join(",", list.stream().map(s -> "\"" + s + "\"").toList()) + "}";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
