package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.service.FormSubmissionService;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.VariantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public (unauthenticated) endpoints used by the Next.js edge middleware and
 * SSR landing page renderer.  No JWT required.
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
@Slf4j
public class PublicLandingController {

    /** Only fast-loop micro-conversions may be rewarded from the browser; CRM stages come via webhooks. */
    private static final Set<String> BROWSER_REWARD_TYPES = Set.of("CTA_CLICK", "FORM_START", "FORM_SUBMIT");

    private final VariantService variantService;
    private final MlServiceClient mlClient;
    private final FormSubmissionService formSubmissionService;

    /**
     * Returns the ACTIVE variant for the given slug.
     * Called by Next.js SSR page to render landing blocks.
     */
    @GetMapping("/l/{slug}")
    public VariantResponse getActiveVariant(@PathVariable String slug) {
        // Slug lookup happens before any tenant is known -> system scope for this read only.
        return TenantContext.callAsSystem(() -> variantService.getActiveBySlug(slug));
    }

    /**
     * Lead form from the landing page (LandingRenderer). The tenant is resolved from the variant
     * the visitor saw, never trusted from the payload (audit 4.9 / §3.4).
     */
    @PostMapping("/form/submit")
    public ResponseEntity<Map<String, Object>> submitForm(@RequestBody Map<String, Object> body) {
        UUID variantId;
        try {
            variantId = UUID.fromString(String.valueOf(body.get("variant_id")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "variant_id required"));
        }
        UUID tenantId = TenantContext.callAsSystem(() -> formSubmissionService.variantOwner(variantId));
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown variant"));
        }
        Map<String, Object> result = new java.util.HashMap<>();
        TenantContext.runAs(tenantId.toString(), () -> result.putAll(formSubmissionService.submit(tenantId, body)));
        return ResponseEntity.ok(result);
    }

    /**
     * L1 reward from the landing page (LandingRenderer posts {decisionId, rewardType}).
     * Forwards to FastAPI /decide/reward, which is idempotent per (decision, type).
     * Audit 2.1: this endpoint did not exist, so no browser reward ever reached an arm.
     */
    @PostMapping("/reward")
    public ResponseEntity<Map<String, Object>> reward(@RequestBody Map<String, Object> body) {
        Object decision = body.getOrDefault("decision_id", body.get("decisionId"));
        Object type     = body.getOrDefault("reward_type", body.get("rewardType"));
        if (decision == null || decision.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "decision_id required"));
        }
        String rewardType = type == null ? "FORM_SUBMIT" : type.toString().toUpperCase();
        if (!BROWSER_REWARD_TYPES.contains(rewardType)) {
            return ResponseEntity.badRequest().body(Map.of("error", "reward_type not allowed from browser"));
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mlClient.post("/decide/reward",
                    Map.of("decision_id", decision.toString(), "reward_type", rewardType), Map.class);
            return ResponseEntity.ok(result == null ? Map.of("status", "ok") : result);
        } catch (Exception e) {
            // Reward failure must never surface to the visitor; log and accept.
            log.warn("Public reward forward failed for decision {}: {}", decision, e.getMessage());
            return ResponseEntity.accepted().body(Map.of("status", "deferred"));
        }
    }
}
