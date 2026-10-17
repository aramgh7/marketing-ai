package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * System vs Baseline Benchmark (vision §21.5).
 *
 * GET /api/v1/benchmark?days=30
 *   Returns incremental lift of Thompson Sampling vs holdout group.
 *   Proxies to FastAPI GET /decide/benchmark/{tenant_id}.
 *
 * PUT /api/v1/benchmark/holdout
 *   Toggle holdout on/off and set rate for the authenticated tenant.
 */
@Slf4j
@RestController
@RequestMapping("/benchmark")
@RequiredArgsConstructor
public class BenchmarkController {

    private final RestTemplate    restTemplate;
    private final AppProperties   appProperties;
    private final TenantRepository tenantRepo;

    @GetMapping
    public Map<?, ?> getBenchmark(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {

        if (days < 7 || days > 365) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days must be 7–365");
        }

        String url = appProperties.getMlServiceUrl()
                + "/decide/benchmark/" + tenantId + "?days=" + days;

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            @SuppressWarnings("unchecked")
            Map<?, ?> result = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.error("Benchmark fetch failed for tenant {}: {}", tenantId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Benchmark unavailable");
        }
    }

    @PutMapping("/holdout")
    public Map<String, Object> updateHoldout(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody HoldoutRequest req) {

        if (req.holdoutRate() != null
                && (req.holdoutRate().doubleValue() < 0 || req.holdoutRate().doubleValue() > 0.20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "holdoutRate must be between 0 and 0.20");
        }

        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        if (req.holdoutEnabled() != null) tenant.setHoldoutEnabled(req.holdoutEnabled());
        if (req.holdoutRate()    != null) tenant.setHoldoutRate(req.holdoutRate());
        tenantRepo.save(tenant);

        if (!tenant.isHoldoutEnabled()) {
            log.warn("Holdout disabled for tenant {} — incremental lift cannot be measured (§21.5)",
                    tenantId);
        }

        return Map.of(
                "holdout_enabled", tenant.isHoldoutEnabled(),
                "holdout_rate",    tenant.getHoldoutRate(),
                "warning", tenant.isHoldoutEnabled() ? null
                        : "Holdout disabled. You lose objective ROI proof (vision §21.5)."
        );
    }

    public record HoldoutRequest(Boolean holdoutEnabled, java.math.BigDecimal holdoutRate) {}
}
