package com.marketingai.controller;

import com.marketingai.dto.webhook.GhlWebhookPayload;
import com.marketingai.service.GhlWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/webhooks/ghl")
@RequiredArgsConstructor
public class GhlWebhookController {

    private final GhlWebhookService webhookService;

    /**
     * Tenant-scoped GHL webhook endpoint.
     * Each tenant configures this URL in GHL: POST /api/v1/webhooks/ghl/{tenantId}
     * No JWT auth — GHL does not send a token; the tenantId in path IS the routing key.
     */
    @PostMapping("/{tenantId}")
    public ResponseEntity<Map<String, String>> handle(
            @PathVariable UUID tenantId,
            @RequestBody GhlWebhookPayload payload) {

        webhookService.process(tenantId, payload);
        return ResponseEntity.ok(Map.of("status", "received"));
    }
}
