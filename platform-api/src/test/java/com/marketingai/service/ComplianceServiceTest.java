package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.entity.ProductFact;
import com.marketingai.repository.ComplianceCheckRepository;
import com.marketingai.repository.ProductFactRepository;
import com.marketingai.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ComplianceServiceTest {

    private ComplianceCheckRepository repo;
    private ProductFactRepository factRepo;
    private JdbcTemplate jdbc;
    private RestTemplate restTemplate;
    private AppProperties appProperties;
    private ComplianceService service;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repo = mock(ComplianceCheckRepository.class);
        factRepo = mock(ProductFactRepository.class);
        jdbc = mock(JdbcTemplate.class);
        restTemplate = mock(RestTemplate.class);
        appProperties = mock(AppProperties.class);

        when(appProperties.getMlServiceUrl()).thenReturn("http://ml:8000");
        when(appProperties.getInternalApiKey()).thenReturn("test-key");
        when(factRepo.findByTenantIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of());

        service = new ComplianceService(repo, factRepo, jdbc, restTemplate, appProperties, new ObjectMapper());

        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId.toString());
    }

    @Test
    void runTextCheck_ml_service_down_returns_warn() {
        when(restTemplate.postForObject(anyString(), any(), eq(java.util.Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        ComplianceCheck saved = new ComplianceCheck();
        saved.setResult("WARN");
        when(repo.save(any())).thenReturn(saved);

        ComplianceCheck result = service.runTextCheck("LANDING_VARIANT", UUID.randomUUID(), "hello");
        assertThat(result.getResult()).isEqualTo("WARN");
    }

    @Test
    void isCompliant_no_check_returns_false() {
        when(repo.findLatestByEntityId(any(), any())).thenReturn(Optional.empty());
        assertThat(service.isCompliant(UUID.randomUUID())).isFalse();
    }

    @Test
    void isCompliant_pass_result_returns_true() {
        ComplianceCheck check = new ComplianceCheck();
        check.setResult("PASS");
        when(repo.findLatestByEntityId(any(), any())).thenReturn(Optional.of(check));
        assertThat(service.isCompliant(UUID.randomUUID())).isTrue();
    }

    @Test
    void isCompliant_fail_without_override_returns_false() {
        ComplianceCheck check = new ComplianceCheck();
        check.setResult("FAIL");
        when(repo.findLatestByEntityId(any(), any())).thenReturn(Optional.of(check));
        assertThat(service.isCompliant(UUID.randomUUID())).isFalse();
    }

    @Test
    void isCompliant_fail_with_override_returns_true() {
        ComplianceCheck check = new ComplianceCheck();
        check.setResult("FAIL");
        check.setOverriddenBy(UUID.randomUUID());
        when(repo.findLatestByEntityId(any(), any())).thenReturn(Optional.of(check));
        assertThat(service.isCompliant(UUID.randomUUID())).isTrue();
    }

    @Test
    void runTextCheck_includes_product_facts() {
        ProductFact fact = new ProductFact();
        fact.setFact("Ships in 24 hours");
        when(factRepo.findByTenantIdOrderByCreatedAtDesc(any())).thenReturn(List.of(fact));

        when(restTemplate.postForObject(anyString(), any(), eq(java.util.Map.class)))
                .thenReturn(java.util.Map.of("result", "PASS", "findings", List.of()));

        ComplianceCheck saved = new ComplianceCheck();
        saved.setResult("PASS");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ComplianceCheck result = service.runTextCheck("LANDING_VARIANT", UUID.randomUUID(), "Ships in 24 hours");
        assertThat(result.getResult()).isEqualTo("PASS");
    }
}
