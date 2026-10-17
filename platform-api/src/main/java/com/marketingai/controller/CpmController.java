package com.marketingai.controller;

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
 * CPM Cost Model endpoints (4-6).
 * Delegates Bayesian regression + LightGBM second opinion to FastAPI POST /predict/cpm.
 * Used in 3-23 profit-per-impression before launch for new creatives.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/predict/cpm")
@RequiredArgsConstructor
public class CpmController {

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;

    /**
     * Estimate CPM for a creative before launch.
     * Query params: creative_id (optional), placement, calendar_tags (comma-separated), cpm_index
     * Delegates to FastAPI /predict/cpm with atom list loaded from DB.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> estimateCpm(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) UUID creativeId,
            @RequestParam(defaultValue = "FEED") String placement,
            @RequestParam(defaultValue = "") String calendarTags,
            @RequestParam(defaultValue = "0.0") double cpmIndex) {

        List<String> atoms = Collections.emptyList();
        if (creativeId != null) {
            atoms = jdbc.queryForList("""
                    SELECT atom_value
                    FROM creative_atoms
                    WHERE creative_id = ?
                    ORDER BY created_at
                    """, String.class, creativeId);
        }

        // Build ML request
        List<String> calendarList = calendarTags.isBlank()
                ? Collections.emptyList()
                : Arrays.asList(calendarTags.split(","));

        Map<String, Object> mlReq = new LinkedHashMap<>();
        mlReq.put("atoms", atoms);
        mlReq.put("placement", placement);
        mlReq.put("calendar_tags", calendarList);
        mlReq.put("cpm_index", cpmIndex);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/predict/cpm",
                    new HttpEntity<>(mlReq, headers), Map.class);
        } catch (Exception ex) {
            log.warn("CPM service unavailable: {}", ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CPM service unavailable");
        }

        if (mlResult == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "CPM service returned null");
        }

        Map<String, Object> resp = new LinkedHashMap<>(mlResult);
        resp.put("creative_id", creativeId);
        resp.put("tenant_id", tenantId);
        return ResponseEntity.ok(resp);
    }
}
