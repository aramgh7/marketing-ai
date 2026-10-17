package com.marketingai.controller;

import com.marketingai.dto.meta.MetaCredentialsRequest;
import com.marketingai.security.TenantContext;
import com.marketingai.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;
    private final JdbcTemplate  jdbc;

    /**
     * Store Meta Marketing API credentials for the current tenant.
     * Tenant admin provides the access_token from Meta Developer console.
     */
    @PutMapping("/me/meta-credentials")
    public ResponseEntity<Void> setMetaCredentials(
            @Valid @RequestBody MetaCredentialsRequest req) {
        tenantService.setMetaCredentials(currentTenant(), req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/settings")
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(tenantService.getSettings(currentTenant()));
    }

    @PatchMapping("/me/settings")
    public ResponseEntity<Void> updateSettings(@RequestBody Map<String, String> body) {
        tenantService.updateSettings(currentTenant(), body);
        return ResponseEntity.noContent().build();
    }

    /**
     * Record OAuth scopes granted by the current tenant (vision §12.7).
     * Called after successful OAuth callback to log what scopes were actually granted.
     */
    @PutMapping("/me/oauth-scopes")
    public ResponseEntity<Void> recordOAuthScopes(
            @RequestParam String provider,
            @RequestBody List<String> scopes) {
        UUID tenantId = currentTenant();
        String[] scopeArr = scopes.toArray(new String[0]);
        jdbc.update("""
            INSERT INTO tenant_oauth_scopes (tenant_id, provider, scopes)
            VALUES (?, ?, ?)
            ON CONFLICT (tenant_id, provider)
            DO UPDATE SET scopes = EXCLUDED.scopes, granted_at = now(), revoked_at = NULL
            """, tenantId, provider.toUpperCase(), scopeArr);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get recorded OAuth scopes for this tenant.
     */
    @GetMapping("/me/oauth-scopes")
    public ResponseEntity<List<Map<String, Object>>> getOAuthScopes() {
        UUID tenantId = currentTenant();
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT provider, scopes, granted_at FROM tenant_oauth_scopes WHERE tenant_id = ?",
            tenantId);
        return ResponseEntity.ok(rows);
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
