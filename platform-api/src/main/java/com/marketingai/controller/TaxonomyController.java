package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Taxonomy Alignment Layer — platform-wide atom registry (arch §15.10, vision §15.12.11).
 *
 * GET  /api/v1/taxonomy          — list atom registry entries (filterable by type/status)
 * POST /api/v1/taxonomy/recalibrate — trigger weekly merge+confirm pass
 * PUT  /api/v1/taxonomy/{id}/merge  — manually merge one entry into another
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/taxonomy")
@RequiredArgsConstructor
public class TaxonomyController {

    private final JdbcTemplate   jdbc;
    private final RestTemplate   restTemplate;
    private final AppProperties  appProperties;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {

        List<Object> params = new java.util.ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT id, atom_type, canonical_value, status, "
            + "appearance_count, tenant_count, merged_into, created_at "
            + "FROM atom_registry WHERE merged_into IS NULL");

        if (type   != null && !type.isBlank())   { sql.append(" AND atom_type = ?"); params.add(type); }
        if (status != null && !status.isBlank()) { sql.append(" AND status = ?");    params.add(status); }
        sql.append(" ORDER BY appearance_count DESC LIMIT 500");

        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @PostMapping("/recalibrate")
    public Map<String, Object> recalibrate(@RequestAttribute("tenantId") UUID tenantId) {
        String url = appProperties.getMlServiceUrl() + "/taxonomy/recalibrate";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(headers), Map.class).getBody();
            log.info("Taxonomy recalibrate triggered by tenant {}: {}", tenantId, result);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.error("Taxonomy recalibrate failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Recalibrate unavailable");
        }
    }

    @PutMapping("/{id}/merge")
    public Map<String, Object> merge(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody MergeRequest req) {

        int count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM atom_registry WHERE id = ? AND merged_into IS NULL",
            Integer.class, id);
        if (count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source entry not found");
        }
        int targetCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM atom_registry WHERE id = ? AND merged_into IS NULL",
            Integer.class, req.intoId());
        if (targetCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Target entry not found");
        }

        jdbc.update("UPDATE atom_registry SET merged_into = ? WHERE id = ?", req.intoId(), id);
        jdbc.update("UPDATE creative_atoms SET registry_id = ? WHERE registry_id = ?", req.intoId(), id);
        log.info("Manual taxonomy merge: {} -> {} by tenant {}", id, req.intoId(), tenantId);

        return Map.of("merged", id.toString(), "into", req.intoId().toString());
    }

    public record MergeRequest(UUID intoId) {}
}
