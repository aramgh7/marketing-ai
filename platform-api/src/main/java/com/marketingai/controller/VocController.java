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
 * Voice of Customer endpoints (vision §9.2, arch §15.8).
 *
 * POST   /api/v1/voc/import          — import raw quotes, extract objections via ML service
 * GET    /api/v1/voc/objections       — list extracted objections for tenant
 * DELETE /api/v1/voc/sources/{id}    — delete a VoC source and its objections
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/voc")
@RequiredArgsConstructor
public class VocController {

    private final JdbcTemplate  jdbc;
    private final RestTemplate  restTemplate;
    private final AppProperties appProperties;

    @PostMapping("/import")
    public Map<String, Object> importQuotes(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody ImportRequest req) {

        if (req.quotes() == null || req.quotes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quotes must not be empty");
        }
        String sourceType = req.sourceType() != null ? req.sourceType() : "OTHER";

        UUID sourceId = jdbc.queryForObject(
            "INSERT INTO voc_sources (tenant_id, source_type, source_name, record_count) "
            + "VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class,
            tenantId, sourceType, req.sourceName(), req.quotes().size());

        String mlUrl = appProperties.getMlServiceUrl() + "/voc/extract-objections";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        Map<String, Object> mlBody = Map.of(
            "quotes", req.quotes(),
            "niche",  req.niche() != null ? req.niche() : "",
            "source_type", sourceType
        );
        List<Map<String, Object>> objections = List.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mlResp = restTemplate.exchange(
                mlUrl, HttpMethod.POST, new HttpEntity<>(mlBody, headers), Map.class).getBody();
            if (mlResp != null && mlResp.get("objections") instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> o = (List<Map<String, Object>>) list;
                objections = o;
            }
        } catch (Exception e) {
            log.warn("VoC ML extraction failed for tenant {}: {}", tenantId, e.getMessage());
        }

        int saved = 0;
        for (Map<String, Object> obj : objections) {
            String cat = String.valueOf(obj.getOrDefault("category", "OTHER")).toUpperCase();
            String text = String.valueOf(obj.getOrDefault("objection_text", ""));
            String quote = String.valueOf(obj.getOrDefault("representative_quote", ""));
            int count = ((Number) obj.getOrDefault("count", 1)).intValue();
            jdbc.update(
                "INSERT INTO voc_objections "
                + "(tenant_id, source_id, category, objection_text, representative_quote, count) "
                + "VALUES (?, ?, ?, ?, ?, ?)",
                tenantId, sourceId, cat, text, quote, count);
            saved++;
        }

        log.info("VoC import: tenant={} source={} quotes={} objections_saved={}",
            tenantId, sourceId, req.quotes().size(), saved);
        return Map.of("source_id", sourceId.toString(), "objections_saved", saved);
    }

    @GetMapping("/objections")
    public List<Map<String, Object>> getObjections(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) String category) {

        if (category != null && !category.isBlank()) {
            return jdbc.queryForList(
                "SELECT id, source_id, category, objection_text, representative_quote, count, created_at "
                + "FROM voc_objections WHERE tenant_id = ? AND category = ? ORDER BY count DESC LIMIT 200",
                tenantId, category.toUpperCase());
        }
        return jdbc.queryForList(
            "SELECT id, source_id, category, objection_text, representative_quote, count, created_at "
            + "FROM voc_objections WHERE tenant_id = ? ORDER BY count DESC LIMIT 200",
            tenantId);
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> deleteSource(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        int owned = jdbc.queryForObject(
            "SELECT COUNT(*) FROM voc_sources WHERE id = ? AND tenant_id = ?",
            Integer.class, id, tenantId);
        if (owned == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VoC source not found");
        }
        jdbc.update("DELETE FROM voc_objections WHERE source_id = ?", id);
        jdbc.update("DELETE FROM voc_sources WHERE id = ?", id);
        log.info("VoC source {} deleted by tenant {}", id, tenantId);
        return ResponseEntity.noContent().build();
    }

    public record ImportRequest(
        List<String> quotes,
        String sourceType,
        String sourceName,
        String niche
    ) {}
}
