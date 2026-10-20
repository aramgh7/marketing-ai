package com.marketingai.service;

import com.marketingai.dto.variant.*;
import com.marketingai.entity.LandingVariant;
import com.marketingai.entity.ThompsonArm;
import com.marketingai.entity.VariantOffer;
import com.marketingai.repository.LandingVariantRepository;
import com.marketingai.repository.ThompsonArmRepository;
import com.marketingai.repository.VariantOfferRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class VariantService {

    private final LandingVariantRepository variantRepo;

    private final RlhaService rlhaService;   // roadmap 9-20
    private final VariantOfferRepository offerRepo;
    private final ThompsonArmRepository armRepo;
    private final ComplianceService complianceService;
    private final OfferValidator offerValidator;
    private final OfferSchemaService offerSchemaService;
    private final JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    // ─── List ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VariantResponse> listVariants(String status) {
        UUID tenantId = currentTenant();
        List<LandingVariant> variants = status != null
                ? variantRepo.findAllByTenantIdAndStatus(tenantId, status)
                : variantRepo.findAllByTenantId(tenantId);
        if (variants.isEmpty()) return List.of();

        // Batch-load arms (1 query) to avoid N+1
        List<UUID> variantIds = variants.stream().map(LandingVariant::getId).toList();
        Map<UUID, ThompsonArm> armByVariantId = armRepo
                .findByVariantIdInAndContextKey(variantIds, "global")
                .stream()
                .collect(Collectors.toMap(ThompsonArm::getVariantId, a -> a));

        // Batch-load offers (1 query)
        Map<UUID, VariantOffer> offerByVariantId = offerRepo
                .findByVariantIdInAndTenantId(variantIds, tenantId)
                .stream()
                .collect(Collectors.toMap(VariantOffer::getVariantId, o -> o));

        return variants.stream()
                .map(v -> toResponse(v,
                        offerByVariantId.get(v.getId()),
                        armByVariantId.get(v.getId())))
                .toList();
    }

    // ─── Get one ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public VariantResponse getVariant(UUID id) {
        LandingVariant v = findOwned(id);
        VariantOffer offer = offerRepo.findByVariantIdAndTenantId(id, currentTenant()).orElse(null);
        return toResponse(v, offer);
    }

    // ─── Create (DRAFT) ──────────────────────────────────────────────────────────

    public VariantResponse createVariant(VariantRequest req) {
        UUID tenantId = currentTenant();
        if (variantRepo.existsByTenantIdAndSlug(tenantId, req.getSlug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug already exists: " + req.getSlug());
        }

        validateOffer(req.getOffer(), tenantId, null);

        LandingVariant v = new LandingVariant();
        v.setTenantId(tenantId);
        v.setName(req.getName());
        v.setSlug(req.getSlug());
        v.setCreativeId(req.getCreativeId());
        v.setBlocks(req.getBlocks() != null ? req.getBlocks() : "[]");
        v.setBlockOrder(req.getBlockOrder() != null ? req.getBlockOrder() : new String[0]);
        v.setStatus("DRAFT");
        variantRepo.save(v);

        validateOffer(req.getOffer(), tenantId, v.getId());
        VariantOffer offer = saveOffer(req.getOffer(), v.getId(), tenantId);
        return toResponse(v, offer);
    }

    // ─── Update ──────────────────────────────────────────────────────────────────

    public VariantResponse updateVariant(UUID id, VariantRequest req) {
        return updateVariant(id, req, null, null);
    }

    /** Roadmap 9-20: edits of generated text slots become RLHA correction pairs. */
    public VariantResponse updateVariant(UUID id, VariantRequest req, UUID editedBy, String editReason) {
        LandingVariant v = findOwned(id);
        String blocksBefore = v.getBlocks();
        String current = v.getStatus();
        if ("ACTIVE".equals(current) || "ARCHIVED".equals(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot edit a variant in status: " + current);
        }

        UUID tenantId = currentTenant();
        if (!v.getSlug().equals(req.getSlug())
                && variantRepo.existsByTenantIdAndSlug(tenantId, req.getSlug())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug already exists: " + req.getSlug());
        }

        validateOffer(req.getOffer(), tenantId, v.getId());

        v.setName(req.getName());
        v.setSlug(req.getSlug());
        v.setCreativeId(req.getCreativeId());
        if (req.getBlocks() != null) v.setBlocks(req.getBlocks());
        if (req.getBlockOrder() != null) v.setBlockOrder(req.getBlockOrder());
        variantRepo.save(v);
        if (req.getBlocks() != null) {
            try {
                rlhaService.captureVariantEdits(tenantId, editedBy, v.getId(), blocksBefore, req.getBlocks(), editReason);
            } catch (Exception e) {
                log.debug("RLHA capture skipped for variant {}: {}", v.getId(), e.getMessage());
            }
        }

        offerRepo.deleteByVariantIdAndTenantId(v.getId(), tenantId);
        VariantOffer offer = saveOffer(req.getOffer(), v.getId(), tenantId);
        return toResponse(v, offer);
    }

    // ─── Workflow: DRAFT → REVIEW ─────────────────────────────────────────────────

    public VariantResponse submitForReview(UUID id) {
        LandingVariant v = findOwned(id);
        if (!"DRAFT".equals(v.getStatus()) && !"PAUSED".equals(v.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT or PAUSED variants can be submitted for review");
        }
        v.setStatus("REVIEW");
        variantRepo.save(v);
        return toResponse(v, offerRepo.findByVariantIdAndTenantId(id, currentTenant()).orElse(null));
    }

    // ─── Workflow: REVIEW → ACTIVE ────────────────────────────────────────────────

    public VariantResponse approveVariant(UUID id, UUID approverId) {
        LandingVariant v = findOwned(id);
        if (!"REVIEW".equals(v.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only REVIEW variants can be approved");
        }
        // Compliance gate: latest check must be PASS or overridden (arch §15.4)
        if (!complianceService.isCompliant(id)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Variant cannot be approved: no passing compliance check. " +
                    "Run /api/v1/compliance/check first, or override an existing check.");
        }
        v.setStatus("ACTIVE");
        v.setApprovedBy(approverId);
        v.setApprovedAt(Instant.now());
        variantRepo.save(v);
        // arch v1.1 §12 #12: single writer for thompson_arms = FastAPI.
        // Arms are created lazily by FastAPI on first /decide call for this variant.
        return toResponse(v, offerRepo.findByVariantIdAndTenantId(id, currentTenant()).orElse(null));
    }

    // ─── Workflow: ACTIVE → PAUSED ────────────────────────────────────────────────

    public VariantResponse pauseVariant(UUID id) {
        LandingVariant v = findOwned(id);
        if (!"ACTIVE".equals(v.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only ACTIVE variants can be paused");
        }
        v.setStatus("PAUSED");
        variantRepo.save(v);
        return toResponse(v, offerRepo.findByVariantIdAndTenantId(id, currentTenant()).orElse(null));
    }

    // ─── Archive ─────────────────────────────────────────────────────────────────

    public VariantResponse archiveVariant(UUID id) {
        LandingVariant v = findOwned(id);
        v.setStatus("ARCHIVED");
        variantRepo.save(v);
        return toResponse(v, offerRepo.findByVariantIdAndTenantId(id, currentTenant()).orElse(null));
    }

    // ─── Public lookup by slug (no auth required) ────────────────────────────────

    @Transactional(readOnly = true)
    public VariantResponse getActiveBySlug(String slug) {
        LandingVariant v = variantRepo.findBySlugAndStatus(slug, "ACTIVE")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No active variant with slug: " + slug));
        VariantOffer offer = offerRepo.findByVariantIdAndTenantId(v.getId(), v.getTenantId()).orElse(null);
        return toResponse(v, offer);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private void validateOffer(VariantOfferRequest req, UUID tenantId, UUID variantId) {
        if (req == null) return;

        // Roadmap 8-8: schema-based offers. The tenant's schema (settings.offer_schema or the request's
        // schema_key) validates the atoms; for INVESTOR_RAISE the margin floor is replaced by a
        // compliance PASS on the offer text.
        String schemaKey = req.getSchemaKey() != null ? req.getSchemaKey()
                : offerSchemaService.tenantSchemaKey(tenantId).orElse(null);
        if (schemaKey != null) {
            OfferSchemaService.Schema schema = offerSchemaService.load(tenantId, schemaKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unknown offer schema: " + schemaKey));
            List<String> errors = OfferSchemaService.validateAtoms(req.getAtoms(), schema.fields(), req.getUrgencySource());
            if (!errors.isEmpty()) {
                logOfferViolations(tenantId, variantId, errors);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Offer schema check failed: " + String.join("; ", errors));
            }
            if (schema.complianceReplacesMargin()) {
                String text = req.getOfferText();
                if (text == null || text.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "offer_text is required: schema " + schemaKey + " requires a compliance PASS on the offer text");
                }
                var check = complianceService.runTextCheck("OFFER", variantId, text);
                if (!"PASS".equals(check.getResult())) {
                    logOfferViolations(tenantId, variantId, List.of("compliance " + check.getResult() + " on offer text"));
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Offer text did not PASS compliance (" + check.getResult() + ") — securities rulebases (8-7)");
                }
                return;   // compliance replaces the margin floor; legacy B2B checks do not apply
            }
        }

        Double minMargin = loadMinMargin(tenantId);
        Map<String, Object> offerMap = new HashMap<>();
        offerMap.put("discount_type",    req.getDiscountType());
        offerMap.put("discount_depth",   req.getDiscountDepth() != null ? req.getDiscountDepth() : 0.0);
        offerMap.put("guarantee_type",   req.getGuaranteeType());
        offerMap.put("guarantee_days",   req.getGuaranteeDays());
        offerMap.put("has_real_urgency", req.getHasRealUrgency());
        offerMap.put("urgency_source",   req.getUrgencySource());
        offerMap.put("estimated_margin", req.getEstimatedMargin());
        offerMap.put("payment_terms",    req.getPaymentTerms());
        OfferValidator.ValidationResult result = offerValidator.validate(offerMap, minMargin);
        if (!result.valid()) {
            // Log violation
            for (String err : result.errors()) {
                try {
                    jdbc.update("""
                        INSERT INTO offer_validity_violations
                            (tenant_id, variant_id, field, reason)
                        VALUES (?, ?, 'offer', ?)
                        """, tenantId, variantId, err);
                } catch (Exception ex) {
                    log.warn("Failed to log offer violation: {}", ex.getMessage());
                }
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Offer validity check failed: " + String.join("; ", result.errors()));
        }
    }

    private void logOfferViolations(UUID tenantId, UUID variantId, List<String> errors) {
        for (String err : errors) {
            try {
                jdbc.update("""
                    INSERT INTO offer_validity_violations (tenant_id, variant_id, field, reason)
                    VALUES (?, ?, 'offer', ?)
                    """, tenantId, variantId, err);
            } catch (Exception ex) {
                log.warn("Failed to log offer violation: {}", ex.getMessage());
            }
        }
    }

    private Double loadMinMargin(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT settings->>'min_margin' AS min_margin FROM tenants WHERE id = ?",
                tenantId);
            if (!rows.isEmpty() && rows.get(0).get("min_margin") != null) {
                return Double.parseDouble(rows.get(0).get("min_margin").toString());
            }
        } catch (Exception e) {
            log.warn("Could not load min_margin for tenant {}: {}", tenantId, e.getMessage());
        }
        return null;
    }

    private LandingVariant findOwned(UUID id) {
        return variantRepo.findByIdAndTenantId(id, currentTenant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found"));
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }

    private VariantOffer saveOffer(VariantOfferRequest req, UUID variantId, UUID tenantId) {
        if (req == null) return null;
        VariantOffer o = new VariantOffer();
        o.setVariantId(variantId);
        o.setTenantId(tenantId);
        o.setDiscountType(req.getDiscountType());
        o.setDiscountDepth(req.getDiscountDepth());
        o.setGuaranteeType(req.getGuaranteeType());
        o.setGuaranteeDays(req.getGuaranteeDays());
        o.setBonus(req.getBonus());
        o.setHasRealUrgency(req.getHasRealUrgency() != null && req.getHasRealUrgency());
        o.setUrgencySource(req.getUrgencySource());
        o.setUrgencyDaysLeft(req.getUrgencyDaysLeft());
        o.setPaymentTerms(req.getPaymentTerms());
        o.setMarginFloor(req.getMarginFloor());
        o.setEstimatedMargin(req.getEstimatedMargin());
        // Mark installment offers for legal review
        boolean isInstallment = req.getPaymentTerms() != null
                && req.getPaymentTerms().toLowerCase().contains("installment");
        o.setRequiresLegalReview(isInstallment);
        // Roadmap 8-8: schema atoms
        o.setSchemaKey(req.getSchemaKey() != null ? req.getSchemaKey()
                : offerSchemaService.tenantSchemaKey(tenantId).orElse(null));
        if (req.getAtoms() != null) {
            try { o.setAtoms(MAPPER.writeValueAsString(req.getAtoms())); } catch (Exception e) { o.setAtoms("{}"); }
        }
        o.setOfferText(req.getOfferText());
        return offerRepo.save(o);
    }

    // Single-variant operations still load arm from DB (1 query, acceptable)
    VariantResponse toResponse(LandingVariant v, VariantOffer offer) {
        ThompsonArm arm = armRepo.findByVariantIdAndContextKey(v.getId(), "global").orElse(null);
        return toResponse(v, offer, arm);
    }

    // Batch-friendly overload used by listVariants (arm pre-loaded, no extra query)
    VariantResponse toResponse(LandingVariant v, VariantOffer offer, ThompsonArm arm) {
        return VariantResponse.builder()
                .id(v.getId())
                .tenantId(v.getTenantId())
                .creativeId(v.getCreativeId())
                .name(v.getName())
                .slug(v.getSlug())
                .status(v.getStatus())
                .blocks(v.getBlocks())
                .blockOrder(v.getBlockOrder())
                .matchScore(v.getMatchScore())
                .approvedBy(v.getApprovedBy())
                .approvedAt(v.getApprovedAt())
                .createdAt(v.getCreatedAt())
                .updatedAt(v.getUpdatedAt())
                .offer(offer == null ? null : VariantOfferResponse.builder()
                        .id(offer.getId())
                        .discountType(offer.getDiscountType())
                        .discountDepth(offer.getDiscountDepth())
                        .guaranteeType(offer.getGuaranteeType())
                        .guaranteeDays(offer.getGuaranteeDays())
                        .bonus(offer.getBonus())
                        .hasRealUrgency(offer.getHasRealUrgency())
                        .urgencySource(offer.getUrgencySource())
                        .urgencyDaysLeft(offer.getUrgencyDaysLeft())
                        .paymentTerms(offer.getPaymentTerms())
                        .marginFloor(offer.getMarginFloor())
                        .schemaKey(offer.getSchemaKey())
                        .atoms(com.marketingai.util.Jsonb.asMap(offer.getAtoms()))
                        .offerText(offer.getOfferText())
                        .build())
                .alpha(arm == null ? null : arm.getAlpha())
                .beta(arm == null ? null : arm.getBeta())
                .totalSamples(arm == null ? null : arm.getTotalSamples())
                .build();
    }
}
