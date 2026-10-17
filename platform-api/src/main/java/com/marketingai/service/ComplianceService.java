package com.marketingai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.repository.ComplianceCheckRepository;
import com.marketingai.repository.ProductFactRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ComplianceService {

    private final ComplianceCheckRepository repo;
    private final ProductFactRepository factRepo;
    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    public ComplianceCheck runTextCheck(String entityType, UUID entityId, String text) {
        UUID tenantId = UUID.fromString(TenantContext.get());

        List<String> facts = factRepo.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream().map(f -> f.getFact()).toList();

        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        body.put("product_facts", facts);
        List<String> trademarks = jdbc.queryForList(
                "SELECT term FROM trademark_watchlist ORDER BY term", String.class);
        body.put("trademark_terms", trademarks);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        ComplianceCheck check = new ComplianceCheck();
        check.setTenantId(tenantId);
        check.setEntityType(entityType);
        check.setEntityId(entityId);
        check.setCheckType("META_POLICY");

        try {
            String url = appProperties.getMlServiceUrl() + "/compliance/check-text";
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);

            if (response != null) {
                String result = (String) response.getOrDefault("result", "WARN");
                check.setResult(result);
                check.setFindings(objectMapper.writeValueAsString(response.get("findings")));
                check.setModelVersion("ml-service");
            } else {
                check.setResult("WARN");
                check.setFindings("[]");
            }
        } catch (Exception ex) {
            log.warn("ml-service compliance check failed for entity {}: {}", entityId, ex.getMessage());
            check.setResult("WARN");
            check.setFindings("[]");
        }

        return repo.save(check);
    }

    @Transactional(readOnly = true)
    public List<ComplianceCheck> getChecks(UUID entityId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        return repo.findByTenantIdAndEntityIdOrderByCreatedAtDesc(tenantId, entityId);
    }

    public ComplianceCheck override(UUID checkId, UUID overriderId, String reason) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        ComplianceCheck check = repo.findById(checkId)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        check.setOverriddenBy(overriderId);
        check.setOverrideReason(reason);
        return repo.save(check);
    }

    /**
     * Returns true if the latest compliance check for an entity is PASS or has been overridden.
     * Used by VariantService.approveVariant() compliance gate.
     */
    @Transactional(readOnly = true)
    public boolean isCompliant(UUID entityId) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Optional<ComplianceCheck> latest = repo.findLatestByEntityId(tenantId, entityId);
        if (latest.isEmpty()) return false;
        ComplianceCheck c = latest.get();
        return "PASS".equals(c.getResult()) || c.getOverriddenBy() != null;
    }
}
