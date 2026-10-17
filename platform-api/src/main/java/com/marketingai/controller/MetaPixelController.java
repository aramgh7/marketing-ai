package com.marketingai.controller;

import com.marketingai.dto.pixel.CreatePixelRequest;
import com.marketingai.dto.pixel.PixelResponse;
import com.marketingai.dto.pixel.UpdatePixelRequest;
import com.marketingai.security.TenantContext;
import com.marketingai.service.MetaPixelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Multi-Pixel management — §6.8 Pixel Pollution Protection.
 *
 * Pixel purposes:
 *  PRIMARY  — clean pixel for confirmed CRM conversions (scale contour)
 *  TEST     — isolated pixel for test campaigns (prevents primary pixel pollution)
 *  PRODUCT  — per-product pixel for multi-product tenants
 *
 * Default pixel is used by CAPIForwardingService when no explicit pixel is specified.
 */
@RestController
@RequestMapping("/pixels")
@RequiredArgsConstructor
public class MetaPixelController {

    private final MetaPixelService pixelService;

    @GetMapping
    public List<PixelResponse> list() {
        return pixelService.listForTenant(currentTenant());
    }

    @PostMapping
    public ResponseEntity<PixelResponse> create(@Valid @RequestBody CreatePixelRequest req) {
        PixelResponse created = pixelService.create(currentTenant(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public PixelResponse get(@PathVariable UUID id) {
        return pixelService.getOwned(id, currentTenant());
    }

    @PutMapping("/{id}")
    public PixelResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePixelRequest req) {
        return pixelService.update(id, currentTenant(), req);
    }

    /** Deactivate pixel (sets status = ARCHIVED). Cannot deactivate the default pixel. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        pixelService.delete(id, currentTenant());
        return ResponseEntity.noContent().build();
    }

    /**
     * Set pixel as the tenant default.
     * Atomically unsets previous default and sets this one.
     * Default pixel is used by CAPI forwarding when no campaign-level pixel override exists.
     */
    @PutMapping("/{id}/default")
    public PixelResponse setDefault(@PathVariable UUID id) {
        return pixelService.setDefault(id, currentTenant());
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
