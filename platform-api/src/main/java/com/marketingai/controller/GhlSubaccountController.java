package com.marketingai.controller;

import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GHL sub-accounts (roadmap 8-9; vision §19.12): one row per location with its role, the webhook
 * secret that location's workflows send, and the private-integration token used to write our ids
 * into contact custom fields.
 *
 *   GET    /ghl/subaccounts
 *   POST   /ghl/subaccounts              {location_id, name, role, webhook_secret, api_key}
 *   DELETE /ghl/subaccounts/{id}
 *   GET    /ghl/integrity                latest funnel integrity checks
 */
@RestController
@RequestMapping("/ghl")
@RequiredArgsConstructor
public class GhlSubaccountController {

    private static final Set<String> ROLES = Set.of("MAIN", "AGENCY", "WEBINAR", "MAILING");

    private final JdbcTemplate jdbc;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets

    @GetMapping("/subaccounts")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT id, location_id, name, role, is_active, created_at,
                       (webhook_secret IS NOT NULL) AS has_secret,
                       (api_key_enc IS NOT NULL)    AS has_api_key
                FROM ghl_subaccounts WHERE tenant_id = ? ORDER BY role, created_at
                """, tenantId));
    }

    @PostMapping("/subaccounts")
    public ResponseEntity<Map<String, Object>> upsert(@RequestAttribute("tenantId") UUID tenantId,
                                                      @RequestBody Map<String, Object> body) {
        String locationId = str(body.get("location_id"));
        if (locationId == null || locationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "location_id required"));
        }
        String role = str(body.getOrDefault("role", "MAIN")).toUpperCase();
        if (!ROLES.contains(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "role must be one of " + ROLES));
        }
        String apiKey = str(body.get("api_key"));
        jdbc.update("""
                INSERT INTO ghl_subaccounts (tenant_id, location_id, name, role, webhook_secret, api_key_enc, is_active)
                VALUES (?, ?, ?, ?, ?, ?, TRUE)
                ON CONFLICT (tenant_id, location_id) DO UPDATE SET
                    name = COALESCE(EXCLUDED.name, ghl_subaccounts.name),
                    role = EXCLUDED.role,
                    webhook_secret = COALESCE(EXCLUDED.webhook_secret, ghl_subaccounts.webhook_secret),
                    api_key_enc = COALESCE(EXCLUDED.api_key_enc, ghl_subaccounts.api_key_enc),
                    is_active = TRUE
                """, tenantId, locationId, str(body.get("name")), role, str(body.get("webhook_secret")),
                vault.seal(apiKey));
        return ResponseEntity.ok(Map.of("location_id", locationId, "role", role, "status", "saved",
                "webhook_url_hint", "/api/v1/webhooks/ghl/" + tenantId + "  (header X-Webhook-Secret = this location's secret)"));
    }

    @DeleteMapping("/subaccounts/{id}")
    public ResponseEntity<Map<String, Object>> deactivate(@RequestAttribute("tenantId") UUID tenantId,
                                                          @PathVariable UUID id) {
        int n = jdbc.update("UPDATE ghl_subaccounts SET is_active = FALSE WHERE id = ? AND tenant_id = ?", id, tenantId);
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("status", "deactivated"));
    }

    @GetMapping("/integrity")
    public ResponseEntity<List<Map<String, Object>>> integrity(@RequestAttribute("tenantId") UUID tenantId,
                                                               @RequestParam(defaultValue = "14") int days) {
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT check_date, form_submits, booked, schedule_sent,
                       delta_booked_schedule, delta_form_booked, verdict, alert_fired
                FROM funnel_integrity_checks
                WHERE tenant_id = ? AND check_date >= CURRENT_DATE - (? * INTERVAL '1 day')
                ORDER BY check_date DESC
                """, tenantId, days));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
