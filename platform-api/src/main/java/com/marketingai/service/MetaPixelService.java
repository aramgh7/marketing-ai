package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.dto.pixel.CreatePixelRequest;
import com.marketingai.dto.pixel.PixelResponse;
import com.marketingai.dto.pixel.UpdatePixelRequest;
import com.marketingai.entity.MetaPixel;
import com.marketingai.repository.MetaPixelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Phase 0-9: Multi-Pixel management — §6.8 Pixel Pollution Protection.
 *
 * Each tenant can have multiple pixels with different purposes:
 *  PRIMARY — clean pixel, only confirmed CRM conversions → scale contour
 *  TEST    — isolated pixel for test campaigns → prevents pollution of primary
 *  PRODUCT — per-product pixel when client has multiple product lines
 *
 * Quality filter (JSONB on the pixel) controls which GHL events qualify for
 * CAPI forwarding on that pixel. Empty = use platform B2B defaults (§16.1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaPixelService {

    private final MetaPixelRepository pixelRepo;
    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<PixelResponse> listForTenant(UUID tenantId) {
        return pixelRepo.findByTenantIdAndStatus(tenantId, "ACTIVE")
                .stream()
                .map(PixelResponse::from)
                .toList();
    }

    public PixelResponse getOwned(UUID id, UUID tenantId) {
        return PixelResponse.from(findOwned(id, tenantId));
    }

    @Transactional
    public PixelResponse create(UUID tenantId, CreatePixelRequest req) {
        // Prevent duplicate pixel_id per tenant
        boolean exists = pixelRepo.findByTenantIdAndStatus(tenantId, "ACTIVE")
                .stream()
                .anyMatch(p -> p.getPixelId().equals(req.getPixelId()));
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pixel ID " + req.getPixelId() + " already exists for this tenant");
        }

        if (req.isDefault()) {
            unsetCurrentDefault(tenantId);
        }

        MetaPixel pixel = new MetaPixel();
        pixel.setTenantId(tenantId);
        pixel.setPixelId(req.getPixelId());
        pixel.setName(req.getName());
        pixel.setPurpose(req.getPurpose() != null ? req.getPurpose() : "PRIMARY");
        pixel.setDefaultPixel(req.isDefault());
        pixel.setQualityFilter(toJson(req.getQualityFilter()));
        pixel.setStatus("ACTIVE");

        if (req.getCapiAccessToken() != null && !req.getCapiAccessToken().isBlank()) {
            pixel.setCapiAccessTokenEnc(
                    req.getCapiAccessToken().getBytes(StandardCharsets.UTF_8));
        }

        MetaPixel saved = pixelRepo.save(pixel);
        log.info("Pixel created: tenant={} pixelId={} purpose={}", tenantId, req.getPixelId(), pixel.getPurpose());
        return PixelResponse.from(saved);
    }

    @Transactional
    public PixelResponse update(UUID id, UUID tenantId, UpdatePixelRequest req) {
        MetaPixel pixel = findOwned(id, tenantId);

        if (req.getName() != null)    pixel.setName(req.getName());
        if (req.getPurpose() != null) pixel.setPurpose(req.getPurpose());
        if (req.getStatus() != null)  pixel.setStatus(req.getStatus());

        if (req.getQualityFilter() != null) {
            pixel.setQualityFilter(toJson(req.getQualityFilter()));
        }

        if (req.getCapiAccessToken() != null && !req.getCapiAccessToken().isBlank()) {
            pixel.setCapiAccessTokenEnc(
                    req.getCapiAccessToken().getBytes(StandardCharsets.UTF_8));
        }

        return PixelResponse.from(pixelRepo.save(pixel));
    }

    @Transactional
    public void delete(UUID id, UUID tenantId) {
        MetaPixel pixel = findOwned(id, tenantId);
        if (pixel.isDefaultPixel()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot deactivate the default pixel. Set another pixel as default first.");
        }
        pixel.setStatus("ARCHIVED");
        pixelRepo.save(pixel);
        log.info("Pixel archived: id={} tenant={}", id, tenantId);
    }

    @Transactional
    public PixelResponse setDefault(UUID id, UUID tenantId) {
        MetaPixel pixel = findOwned(id, tenantId);
        if (!"ACTIVE".equals(pixel.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot set an archived pixel as default.");
        }
        unsetCurrentDefault(tenantId);
        pixel.setDefaultPixel(true);
        return PixelResponse.from(pixelRepo.save(pixel));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private MetaPixel findOwned(UUID id, UUID tenantId) {
        return pixelRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Pixel not found: " + id));
    }

    /** Unset is_default on all pixels of this tenant. Called before setting a new default. */
    private void unsetCurrentDefault(UUID tenantId) {
        jdbc.update(
                "UPDATE meta_pixels SET is_default = false WHERE tenant_id = ?",
                tenantId);
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
