package com.marketingai.controller;

import com.marketingai.dto.variant.VariantRequest;
import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.entity.ThompsonArm;
import com.marketingai.repository.ThompsonArmRepository;
import com.marketingai.security.TenantContext;
import com.marketingai.service.VariantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/landings")
@RequiredArgsConstructor
public class VariantController {

    private final VariantService variantService;
    private final ThompsonArmRepository thompsonArmRepo;

    @GetMapping
    public List<VariantResponse> list(@RequestParam(required = false) String status,
                                      @RequestParam(value = "set_id", required = false) UUID setId) {
        return variantService.listVariants(status, setId);   // roadmap Phase 10: set_id filter
    }

    @GetMapping("/{id}")
    public VariantResponse get(@PathVariable UUID id) {
        return variantService.getVariant(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VariantResponse create(@Valid @RequestBody VariantRequest req) {
        return variantService.createVariant(req);
    }

    @PutMapping("/{id}")
    public VariantResponse update(@PathVariable UUID id, @Valid @RequestBody VariantRequest req,
                                  @RequestAttribute(value = "userId", required = false) UUID userId,
                                  @RequestParam(value = "edit_reason", required = false) String editReason) {
        return variantService.updateVariant(id, req, userId, editReason);   // roadmap 9-20: edits feed RLHA
    }

    /** Transition DRAFT/PAUSED → REVIEW */
    @PostMapping("/{id}/submit")
    public VariantResponse submitForReview(@PathVariable UUID id) {
        return variantService.submitForReview(id);
    }

    /** Transition REVIEW → ACTIVE, creates ThompsonArm */
    @PostMapping("/{id}/approve")
    public VariantResponse approve(@PathVariable UUID id) {
        // JWT subject = userId (see JwtUtil.generate)
        String principal = SecurityContextHolder.getContext().getAuthentication().getName();
        UUID approverId = UUID.fromString(principal);
        return variantService.approveVariant(id, approverId);
    }

    /** Transition ACTIVE → PAUSED */
    @PostMapping("/{id}/pause")
    public VariantResponse pause(@PathVariable UUID id) {
        return variantService.pauseVariant(id);
    }

    /** Archive — final state */
    @PostMapping("/{id}/archive")
    public VariantResponse archive(@PathVariable UUID id) {
        return variantService.archiveVariant(id);
    }

    /** A/B arms — Bayesian posteriors for admin dashboard */
    @GetMapping("/arms")
    public List<Map<String, Object>> arms() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        UUID tenantId = UUID.fromString(raw);

        List<ThompsonArm> all = thompsonArmRepo.findAllByTenantId(tenantId);
        double totalAlpha = all.stream().mapToDouble(a -> a.getAlpha()).sum();

        return all.stream().map(arm -> {
            double alpha = arm.getAlpha();
            double beta  = arm.getBeta();
            double mean  = alpha / (alpha + beta);
            double winProb = totalAlpha > 0 ? alpha / totalAlpha : 0.0;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variant_id",      arm.getVariantId());
            row.put("variant_name",    arm.getVariantId().toString());
            row.put("alpha",           alpha);
            row.put("beta",            beta);
            row.put("win_probability", winProb);
            row.put("posterior_mean",  mean);
            return row;
        }).collect(Collectors.toList());
    }

}
