package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.controller.VocController;
import com.marketingai.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VocController (vision §9.2):
 * verifies import flow, objection listing, and source deletion.
 */
class VocServiceTest {

    private JdbcTemplate jdbc;
    private RestTemplate restTemplate;
    private AppProperties appProperties;
    private VocController controller;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        restTemplate = mock(RestTemplate.class);
        appProperties = mock(AppProperties.class);

        when(appProperties.getMlServiceUrl()).thenReturn("http://ml:8000");
        when(appProperties.getInternalApiKey()).thenReturn("test-key");

        controller = new VocController(jdbc, restTemplate, appProperties);
        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId.toString());
    }

    @Test
    void importQuotes_persists_source_and_objections() {
        UUID sourceId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("INSERT INTO voc_sources"), eq(UUID.class),
                any(), any(), any(), anyInt()))
                .thenReturn(sourceId);

        List<Map<String, Object>> mlObjList = List.of(
                Map.of("category", "PRICE", "objection_text", "Too expensive",
                        "representative_quote", "way too pricey", "count", 3)
        );
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> responseEntity = mock(ResponseEntity.class);
        when(responseEntity.getBody()).thenReturn(Map.of("objections", mlObjList, "method", "llm"));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(responseEntity);

        var req = new VocController.ImportRequest(
                List.of("too expensive", "costs too much"), "REVIEW", "Google Reviews", null);
        Map<String, Object> result = controller.importQuotes(tenantId, req);

        assertThat(result.get("source_id")).isEqualTo(sourceId.toString());
        assertThat((int) result.get("objections_saved")).isEqualTo(1);
        verify(jdbc, times(1)).update(contains("INSERT INTO voc_objections"), any(), any(),
                eq("PRICE"), any(), any(), anyInt());
    }

    @Test
    void getObjections_delegates_to_jdbc() {
        when(jdbc.queryForList(anyString(), eq(tenantId))).thenReturn(List.of(
                Map.of("category", "TRUST", "objection_text", "Need proof", "count", 5)
        ));

        List<Map<String, Object>> result = controller.getObjections(tenantId, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("category")).isEqualTo("TRUST");
    }

    @Test
    void deleteSource_not_owned_throws_404() {
        UUID sourceId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> controller.deleteSource(tenantId, sourceId))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
