package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.controller.MediaComplianceController;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.repository.ComplianceCheckRepository;
import com.marketingai.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaComplianceTest {

    private ComplianceCheckRepository repo;
    private RestTemplate restTemplate;
    private AppProperties appProperties;
    private JdbcTemplate jdbc;
    private MediaComplianceController controller;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repo = mock(ComplianceCheckRepository.class);
        restTemplate = mock(RestTemplate.class);
        appProperties = mock(AppProperties.class);
        jdbc = mock(JdbcTemplate.class);

        when(appProperties.getMlServiceUrl()).thenReturn("http://ml:8000");
        when(appProperties.getInternalApiKey()).thenReturn("test-key");
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller = new MediaComplianceController(
                repo, restTemplate, appProperties, jdbc, new ObjectMapper());

        tenantId = UUID.randomUUID();
        TenantContext.set(tenantId.toString());
    }

    @Test
    void checkMedia_allPass_returns_pass() {
        UUID entityId = UUID.randomUUID();
        List<Map<String, Object>> individual = List.of(
                Map.of("check_type", "FACE", "result", "PASS", "findings", List.of()),
                Map.of("check_type", "MUSIC", "result", "PASS", "findings", List.of()),
                Map.of("check_type", "AI_LABEL", "result", "PASS", "findings", List.of())
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(
                Map.of("result", "PASS", "checks", Map.of(), "individual", individual));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity_type", "CREATIVE");
        body.put("entity_id", entityId.toString());
        body.put("asset_url", "s3://bucket/video.mp4");
        body.put("metadata", Map.of());

        ResponseEntity<Map<String, Object>> resp = controller.checkMedia(tenantId, body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("result", "PASS");
        assertThat(resp.getBody()).containsEntry("saved_checks", 3);
    }

    @Test
    void checkMedia_faceNoRelease_saves_fail_check() {
        UUID entityId = UUID.randomUUID();
        List<Map<String, Object>> individual = List.of(
                Map.of("check_type", "FACE", "result", "FAIL",
                        "findings", List.of(Map.of("rule", "NO_MODEL_RELEASE"))),
                Map.of("check_type", "MUSIC", "result", "PASS", "findings", List.of()),
                Map.of("check_type", "AI_LABEL", "result", "PASS", "findings", List.of())
        );
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(
                Map.of("result", "FAIL", "checks", Map.of(), "individual", individual));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity_type", "CREATIVE");
        body.put("entity_id", entityId.toString());
        body.put("asset_url", "s3://bucket/video.mp4");
        body.put("metadata", Map.of("detected_faces",
                List.of(Map.of("face_id", "f1", "has_model_release", false))));

        ResponseEntity<Map<String, Object>> resp = controller.checkMedia(tenantId, body);
        assertThat(resp.getBody()).containsEntry("result", "FAIL");
        verify(repo, times(3)).save(any(ComplianceCheck.class));
    }

    @Test
    void checkMedia_ml_service_down_returns_warn() {
        UUID entityId = UUID.randomUUID();
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entity_type", "CREATIVE");
        body.put("entity_id", entityId.toString());
        body.put("asset_url", "s3://bucket/video.mp4");

        ResponseEntity<Map<String, Object>> resp = controller.checkMedia(tenantId, body);
        assertThat(resp.getBody()).containsEntry("result", "WARN");
    }

    @Test
    void getMediaChecks_filters_media_types_only() {
        UUID entityId = UUID.randomUUID();
        ComplianceCheck face = new ComplianceCheck();
        face.setCheckType("FACE");
        face.setResult("PASS");
        face.setTenantId(tenantId);
        ComplianceCheck text = new ComplianceCheck();
        text.setCheckType("META_POLICY");
        text.setResult("PASS");
        text.setTenantId(tenantId);

        when(repo.findByTenantIdAndEntityIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of(face, text));

        ResponseEntity<List<ComplianceCheck>> resp =
                controller.getMediaChecks(tenantId, entityId);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).getCheckType()).isEqualTo("FACE");
    }
}
