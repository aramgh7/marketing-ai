package com.marketingai.controller;

import com.marketingai.dto.meta.MetaCredentialsRequest;
import com.marketingai.security.TenantContext;
import com.marketingai.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

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

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
