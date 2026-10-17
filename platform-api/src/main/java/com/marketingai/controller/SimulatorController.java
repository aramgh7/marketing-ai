package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * What-If Simulator proxy (vision §21.4).
 *
 * POST /api/v1/simulate
 *   Forwards to FastAPI POST /simulate/ with tenant_id injected from JWT context.
 *
 * Scenarios: budget | power | arm_disable
 */
@Slf4j
@RestController
@RequestMapping("/simulate")
@RequiredArgsConstructor
public class SimulatorController {

    private final RestTemplate  restTemplate;
    private final AppProperties appProperties;

    @PostMapping
    public Map<?, ?> simulate(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        // Inject tenant_id from JWT — client must not be able to query other tenants
        body = new java.util.HashMap<>(body);
        body.put("tenant_id", tenantId.toString());

        String url = appProperties.getMlServiceUrl() + "/simulate/";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            @SuppressWarnings("unchecked")
            Map<?, ?> result = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            return result != null ? result : Map.of();
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new ResponseStatusException(
                    HttpStatus.valueOf(e.getStatusCode().value()), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Simulate call failed for tenant {}: {}", tenantId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Simulator unavailable");
        }
    }
}
