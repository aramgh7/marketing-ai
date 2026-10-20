package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.repository.ComplianceCheckRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Media compliance endpoints (4-5): FACE, MUSIC, AI_LABEL checks for renders / footage.
 * Delegates detection to FastAPI POST /compliance/media/check.
 */
@Slf4j
@RestController
@RequestMapping("/compliance/media")
@RequiredArgsConstructor
public class MediaComplianceController {

    private final ComplianceCheckRepository repo;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Submit an asset for all three media checks (FACE + MUSIC + AI_LABEL).
     * Stores one ComplianceCheck row per check_type that has findings.
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkMedia(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String entityType = (String) body.getOrDefault("entity_type", "CREATIVE");
        UUID entityId = UUID.fromString((String) body.get("entity_id"));
        String assetUrl = (String) body.getOrDefault("asset_url", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) body.getOrDefault("metadata", Map.of());

        // Persist the asset record
        jdbc.update("""
                INSERT INTO media_compliance_assets
                    (id, tenant_id, entity_type, entity_id, asset_url, metadata)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                UUID.randomUUID(), tenantId, entityType, entityId,
                assetUrl, toJson(metadata));

        // Call FastAPI
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        Map<String, Object> mlReq = Map.of("asset_url", assetUrl, "metadata", metadata);
        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult;
        try {
            mlResult = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/compliance/media/check",
                    new HttpEntity<>(mlReq, headers), Map.class);
        } catch (Exception ex) {
            log.warn("FastAPI media compliance unavailable for entity {}: {}", entityId, ex.getMessage());
            mlResult = Map.of("result", "WARN", "checks", Map.of(), "findings", List.of());
        }

        if (mlResult == null) {
            mlResult = Map.of("result", "WARN", "checks", Map.of(), "findings", List.of());
        }

        // Persist one row per check type
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> individual =
                (List<Map<String, Object>>) mlResult.getOrDefault("individual", List.of());

        List<ComplianceCheck> saved = new ArrayList<>();
        for (Map<String, Object> singleCheck : individual) {
            ComplianceCheck cc = new ComplianceCheck();
            cc.setTenantId(tenantId);
            cc.setEntityType(entityType);
            cc.setEntityId(entityId);
            cc.setCheckType((String) singleCheck.getOrDefault("check_type", "UNKNOWN"));
            cc.setResult((String) singleCheck.getOrDefault("result", "WARN"));
            try {
                cc.setFindings(objectMapper.writeValueAsString(
                        singleCheck.getOrDefault("findings", List.of())));
            } catch (Exception e) {
                cc.setFindings("[]");
            }
            cc.setModelVersion("ml-service-media");
            saved.add(repo.save(cc));
        }

        log.info("Media compliance check for entity {} → result={}", entityId, mlResult.get("result"));
        return ResponseEntity.ok(Map.of(
                "result", mlResult.get("result"),
                "checks", mlResult.getOrDefault("checks", Map.of()),
                "saved_checks", saved.size()
        ));
    }

    /** List all media compliance checks for an entity. */
    @GetMapping("/{entityId}")
    public ResponseEntity<List<ComplianceCheck>> getMediaChecks(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID entityId) {
        return ResponseEntity.ok(
                repo.findByTenantIdAndEntityIdOrderByCreatedAtDesc(tenantId, entityId)
                        .stream()
                        .filter(c -> List.of("FACE", "MUSIC", "AI_LABEL").contains(c.getCheckType()))
                        .toList()
        );
    }

    /** Override a media compliance check (marketer confirms e.g. model release uploaded). */
    @PostMapping("/{checkId}/override")
    public ResponseEntity<ComplianceCheck> override(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID checkId,
            @RequestBody Map<String, String> body) {

        ComplianceCheck check = repo.findById(checkId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String reason = body.get("reason");
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Override reason must not be blank");
        }

        UUID overriderId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());
        check.setOverriddenBy(overriderId);
        check.setOverrideReason(reason);
        return ResponseEntity.ok(repo.save(check));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
