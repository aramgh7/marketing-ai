package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.config.AppProperties;
import com.marketingai.dto.webhook.GhlWebhookPayload;
import com.marketingai.service.GhlWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/webhooks/ghl")
@RequiredArgsConstructor
public class GhlWebhookController {

    private final GhlWebhookService webhookService;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;

    /**
     * Tenant-scoped GHL webhook endpoint.
     * Each tenant configures this URL in GHL: POST /api/v1/webhooks/ghl/{tenantId}
     * No JWT auth — GHL does not send a token; the tenantId in path IS the routing key.
     */
    @PostMapping("/{tenantId}")
    public ResponseEntity<Map<String, String>> handle(
            @PathVariable UUID tenantId,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret,
            @RequestBody GhlWebhookPayload payload) {

        // Audit §3.4: anyone who knew a tenant UUID could post "won" opportunities and fire Purchase
        // CAPI events. GHL custom webhooks can send a header or a query parameter; both are accepted.
        String provided = headerSecret != null ? headerSecret : querySecret;
        // Roadmap 8-9: every GHL sub-account (location) has its own secret and role
        Map<String, Object> subaccount = TenantContext.callAsSystem(() -> subaccountFor(tenantId, payload.getLocationId()));
        String expected = subaccount != null && subaccount.get("webhook_secret") != null
                ? subaccount.get("webhook_secret").toString()
                : TenantContext.callAsSystem(() -> expectedSecret(tenantId));
        if (!secretMatches(provided, expected)) {
            log.warn("GHL webhook rejected for tenant {} location {}: bad or missing secret", tenantId, payload.getLocationId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        // Opportunities (deal won/lost → Purchase CAPI, converted sessions) are trusted from MAIN only
        String role = subaccount == null ? "MAIN" : String.valueOf(subaccount.get("role"));
        if ("OpportunityStatusUpdate".equals(payload.getType()) && !"MAIN".equals(role)) {
            log.info("GHL OpportunityStatusUpdate ignored from {} location {} (tenant {})", role, payload.getLocationId(), tenantId);
            return ResponseEntity.ok(Map.of("status", "ignored", "reason", "opportunities are read from the MAIN location only"));
        }

        // Path tenant is the RLS scope for everything this webhook touches.
        TenantContext.runAs(tenantId.toString(), () -> webhookService.process(tenantId, payload));
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /** Active sub-account row for the payload's locationId, or null (legacy single-location setup). */
    private Map<String, Object> subaccountFor(UUID tenantId, String locationId) {
        if (locationId == null || locationId.isBlank()) return null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT role, webhook_secret FROM ghl_subaccounts WHERE tenant_id = ? AND location_id = ? AND is_active = TRUE",
                    tenantId, locationId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("subaccount lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** Per-tenant secret (tenants.settings.ghl_webhook_secret) with the global one as fallback. */
    private String expectedSecret(UUID tenantId) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT settings->>'ghl_webhook_secret' FROM tenants WHERE id = ?", String.class, tenantId);
            if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) return rows.get(0);
        } catch (Exception e) {
            log.debug("tenant secret lookup failed: {}", e.getMessage());
        }
        return appProperties.getGhlWebhookSecret();
    }

    /** Constant-time comparison; an empty/missing configured secret fails closed. */
    static boolean secretMatches(String provided, String expected) {
        if (expected == null || expected.isBlank() || provided == null || provided.isEmpty()) return false;
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }
}
