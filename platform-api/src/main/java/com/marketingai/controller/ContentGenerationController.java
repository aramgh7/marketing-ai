package com.marketingai.controller;

import com.marketingai.service.RlhaService;
import com.marketingai.config.AppProperties;
import com.marketingai.repository.ProductFactRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/landings")
@RequiredArgsConstructor
public class ContentGenerationController {

    private final RestTemplate restTemplate;

    private final RlhaService rlhaService;   // roadmap 9-20
    private final AppProperties appProperties;
    private final ProductFactRepository factRepo;

    @PostMapping("/{variantId}/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @PathVariable UUID variantId,
            @RequestBody Map<String, Object> body) {

        UUID tenantId = UUID.fromString(TenantContext.get());

        List<String> facts = factRepo.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(f -> f.getFact()).toList();

        Map<String, Object> req = new HashMap<>(body);
        req.put("tenant_id", tenantId.toString());
        req.putIfAbsent("product_facts", facts);
        // Roadmap 9-20 (vision §13.3): the marketer's earlier edits become few-shot style examples
        if (!req.containsKey("rlha_few_shots")) {
            req.put("rlha_few_shots", rlhaService.fewShots(tenantId, "landing_block"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/content/generate",
                    new HttpEntity<>(req, headers), Map.class);
            // Roadmap 9-20: record every generated slot so a later edit can be matched to what the system wrote
            if (result != null && result.get("slots") instanceof Map<?, ?> slots) {
                String blockEventId = body.get("block_event_id") == null ? null : String.valueOf(body.get("block_event_id"));
                String model = result.get("model") == null ? null : String.valueOf(result.get("model"));
                for (Map.Entry<?, ?> e : slots.entrySet()) {
                    try {
                        rlhaService.recordGeneration(tenantId, "LANDING_VARIANT", variantId, blockEventId,
                                String.valueOf(e.getKey()), String.valueOf(e.getValue()), "landing_block", model);
                    } catch (Exception ex) {
                        log.debug("content_generations insert skipped: {}", ex.getMessage());
                    }
                }
            }
            return ResponseEntity.ok(result != null ? result : Map.of());
        } catch (Exception ex) {
            log.error("content generation failed for variant {}: {}", variantId, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{variantId}/match-score")
    public ResponseEntity<Map<String, Object>> matchScore(
            @PathVariable UUID variantId,
            @RequestBody Map<String, Object> body) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/content/match-score",
                    new HttpEntity<>(body, headers), Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of());
        } catch (Exception ex) {
            log.error("match-score failed for variant {}: {}", variantId, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
    }
}
