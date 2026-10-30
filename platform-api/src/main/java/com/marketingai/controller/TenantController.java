package com.marketingai.controller;

import com.marketingai.dto.meta.MetaCredentialsRequest;
import com.marketingai.security.TenantContext;
import com.marketingai.service.MetaApiService;
import com.marketingai.service.TenantService;
import com.marketingai.service.TokenVault;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService  tenantService;
    private final JdbcTemplate   jdbc;
    private final TokenVault     vault;
    private final MetaApiService metaApi;

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

    /** Roadmap Phase 11: what the publish flow needs (never the token itself). */
    @GetMapping("/me/meta-credentials")
    public ResponseEntity<Map<String, Object>> getMetaCredentials() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT meta_ad_account_id, meta_app_id, meta_page_id, meta_instagram_actor_id, meta_token_status,
                       (meta_access_token_enc IS NOT NULL) AS has_token, meta_write_stage
                FROM tenants WHERE id = ?
                """, currentTenant());
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("meta_ad_account_id", r.get("meta_ad_account_id"));
        out.put("meta_app_id", r.get("meta_app_id"));
        out.put("meta_page_id", r.get("meta_page_id"));
        out.put("meta_instagram_actor_id", r.get("meta_instagram_actor_id"));
        out.put("meta_token_status", r.get("meta_token_status"));
        out.put("has_token", r.get("has_token"));
        out.put("meta_write_stage", r.get("meta_write_stage"));
        return ResponseEntity.ok(out);
    }

    /** Live check: decrypt stored token and call Meta GET /me. */
    @PostMapping("/me/meta-token/verify")
    public ResponseEntity<Map<String, Object>> verifyMetaToken() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT meta_access_token_enc FROM tenants WHERE id = ?", currentTenant());
        if (rows.isEmpty() || rows.get(0).get("meta_access_token_enc") == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", false);
            out.put("error", "No token stored");
            return ResponseEntity.ok(out);
        }
        String token = vault.openOrNull((byte[]) rows.get(0).get("meta_access_token_enc"));
        if (token == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("valid", false);
            out.put("error", "Token decryption failed");
            return ResponseEntity.ok(out);
        }
        return ResponseEntity.ok(metaApi.verifyToken(token));
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
