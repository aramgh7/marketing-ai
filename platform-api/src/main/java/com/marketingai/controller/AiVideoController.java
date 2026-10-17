package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
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
 * AI Video / Voice-as-Atom endpoints (5-6).
 * Gate: tenants.settings.ai_video_enabled must be true.
 * Mandatory A/B acceptance: P(AI >= live) > 0.9 before scale (vision §5.2).
 * Delegates payload-building and A/B computation to FastAPI.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai-video")
@RequiredArgsConstructor
public class AiVideoController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    // ── POST /request ─────────────────────────────────────────────────────────

    @PostMapping("/request")
    public ResponseEntity<Map<String, Object>> createRequest(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        // Feature flag gate
        checkAiVideoEnabled(tenantId);

        String provider = (String) body.getOrDefault("provider", "MOCK");
        String scriptText = (String) body.getOrDefault("script_text", "");
        @SuppressWarnings("unchecked")
        Map<String, Object> voiceSettings = (Map<String, Object>) body.getOrDefault("voice_settings", Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> formatSettings = (Map<String, Object>) body.getOrDefault("format_settings", Map.of());

        if (scriptText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "script_text must not be blank");
        }

        // Delegate to FastAPI: get provider payload + validate provider
        Map<String, Object> mlReq = Map.of(
                "provider", provider,
                "script_text", scriptText,
                "voice_settings", voiceSettings,
                "format_settings", formatSettings
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> mlResult = callFastApi("/ai-video/provider-payload", mlReq);

        // Extract voice atoms from FastAPI
        @SuppressWarnings("unchecked")
        Map<String, Object> atomReq = Map.of("voice_settings", voiceSettings);
        @SuppressWarnings("unchecked")
        Map<String, Object> atomResult = callFastApi("/ai-video/voice-atoms", atomReq);

        // Persist request record
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_video_requests
                    (id, tenant_id, provider, script_text, status, requires_ai_label)
                VALUES (?, ?, ?, ?, 'PENDING', TRUE)
                """,
                requestId, tenantId, provider.toUpperCase(), scriptText);

        log.info("AI video request {} created for tenant {} via provider {}", requestId, tenantId, provider);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("request_id", requestId);
        resp.put("provider_payload", mlResult.get("payload"));
        resp.put("requires_ai_label", mlResult.getOrDefault("requires_ai_label", true));
        resp.put("voice_atoms", atomResult.getOrDefault("atoms", List.of()));
        return ResponseEntity.ok(resp);
    }

    // ── GET / ─────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, provider, script_text, status, provider_job_id,
                       result_url, approved_by, approved_at, requires_ai_label, created_at
                FROM ai_video_requests
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 50
                """, tenantId);
        return ResponseEntity.ok(rows);
    }

    // ── POST /{id}/approve ────────────────────────────────────────────────────

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM ai_video_requests WHERE id = ? AND tenant_id = ?",
                id, tenantId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI video request not found");
        }
        String status = (String) rows.get(0).get("status");
        if (!"COMPLETED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Can only approve COMPLETED requests (current status: " + status + ")");
        }

        UUID approverId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());

        jdbc.update("""
                UPDATE ai_video_requests
                SET approved_by = ?, approved_at = now(), updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """, approverId, id, tenantId);

        return ResponseEntity.ok(Map.of("approved", true, "request_id", id));
    }

    // ── GET /{id}/ab-acceptance ───────────────────────────────────────────────

    @GetMapping("/{id}/ab-acceptance")
    public ResponseEntity<Map<String, Object>> abAcceptance(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int aiConversions,
            @RequestParam(defaultValue = "0") int aiSessions,
            @RequestParam(defaultValue = "0") int liveConversions,
            @RequestParam(defaultValue = "0") int liveSessions) {

        // Verify request belongs to tenant
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM ai_video_requests WHERE id = ? AND tenant_id = ?",
                id, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI video request not found");
        }

        Map<String, Object> mlReq = Map.of(
                "ai_conversions", aiConversions,
                "ai_sessions", aiSessions,
                "live_conversions", liveConversions,
                "live_sessions", liveSessions
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> result = callFastApi("/ai-video/ab-acceptance", mlReq);

        // Persist A/B result
        Object pAiGteLive = result.get("p_ai_gte_live");
        String acceptance = (String) result.getOrDefault("acceptance", "INSUFFICIENT_DATA");

        jdbc.update("""
                INSERT INTO ai_video_ab_results
                    (id, tenant_id, request_id, ai_conversions, ai_sessions,
                     live_conversions, live_sessions, p_ai_gte_live, acceptance)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, id,
                aiConversions, aiSessions, liveConversions, liveSessions,
                pAiGteLive, acceptance);

        Map<String, Object> resp = new LinkedHashMap<>(result);
        resp.put("request_id", id);
        return ResponseEntity.ok(resp);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void checkAiVideoEnabled(UUID tenantId) {
        List<Boolean> rows = jdbc.queryForList(
                "SELECT (settings->>'ai_video_enabled')::boolean FROM tenants WHERE id = ?",
                Boolean.class, tenantId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found");
        }
        if (!Boolean.TRUE.equals(rows.get(0))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "AI video is disabled for this tenant. Enable ai_video_enabled in tenant settings.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callFastApi(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            Map<String, Object> result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + path,
                    new HttpEntity<>(body, headers), Map.class);
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "FastAPI returned null from " + path);
            }
            return result;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception ex) {
            log.warn("FastAPI {} call failed: {}", path, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "ML service error: " + ex.getMessage());
        }
    }
}
