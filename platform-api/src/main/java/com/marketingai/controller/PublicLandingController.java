package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.entity.TenantDomain;
import com.marketingai.service.DomainVerifyMath;
import com.marketingai.service.FormSubmissionService;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.TenantDomainService;
import com.marketingai.service.VariantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final TenantDomainService domainService;   // roadmap Phase 10

    /** Roadmap Phase 10: light per-runner-key rate limit for /public/decide (requests per minute). */
    static final int DECIDE_PER_MINUTE = 600;
    private final Map<String, long[]> decideWindow = new ConcurrentHashMap<>();

    /**
     * Returns the ACTIVE variant for the given slug.
     * Called by Next.js SSR page to render landing blocks.
     * Roadmap Phase 10: with {@code host} (a verified client domain) the slug is scoped to that tenant.
     */
    @GetMapping("/l/{slug}")
    public VariantResponse getActiveVariant(@PathVariable String slug,
                                            @RequestParam(required = false) String host) {
        // Slug lookup happens before any tenant is known -> system scope for this read only.
        return TenantContext.callAsSystem(() -> resolveActive(slug, host));
    }

    private VariantResponse resolveActive(String slug, String host) {
        if (host == null || host.isBlank()) return variantService.getActiveBySlug(slug);
        TenantDomain d = domainService.resolvePublic(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown host: " + host));
        return variantService.getActiveBySlugForTenant(d.getTenantId(), slug);
    }

    /** Roadmap Phase 10: tenant for a custom host (used by the Next.js middleware). VERIFIED only. */
    @GetMapping("/domain/{host}")
    public Map<String, Object> domain(@PathVariable String host) {
        TenantDomain d = domainService.resolvePublic(host)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown host: " + host));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenantId", d.getTenantId());
        out.put("host", d.getHost());
        out.put("kind", d.getKind());
        out.put("hosting", d.getHosting());
        out.put("defaultSlug", d.getDefaultSlug());
        return out;
    }

    /**
     * Roadmap Phase 10: variant decision for the self-hosted landing runner. The runner authenticates with a
     * per-domain key (never the internal ML key); the key must belong to the tenant that owns the slug.
     */
    @PostMapping("/decide")
    public ResponseEntity<Map<String, Object>> decide(@RequestHeader(value = "X-Runner-Key", required = false) String runnerKey,
                                                      @RequestBody Map<String, Object> body) {
        if (runnerKey == null || runnerKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "X-Runner-Key required"));
        }
        UUID keyTenant = domainService.tenantForRunnerKey(runnerKey).orElse(null);
        if (keyTenant == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "invalid runner key"));
        }
        if (!allowDecide(DomainVerifyMath.sha256Hex(runnerKey.trim()))) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate limit"));
        }
        Object slugObj = body.get("slug");
        if (slugObj == null || String.valueOf(slugObj).isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "slug required"));
        }
        String slug = String.valueOf(slugObj);
        String host = body.get("host") == null ? null : String.valueOf(body.get("host"));
        VariantResponse variant;
        try {
            variant = TenantContext.callAsSystem(() -> resolveActive(slug, host));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
        }
        if (!keyTenant.equals(variant.getTenantId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "runner key does not own this landing"));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("tenant_id", variant.getTenantId().toString());
        req.put("session_id", body.get("session_id"));
        req.put("context_key", "global");
        req.put("context", body.get("context") instanceof Map<?, ?> m ? m : Map.of());
        // Roadmap Phase 11-1: the runner forwards the creative id (or the Meta ad id inside context) unchanged.
        if (body.get("creative_id") != null) req.put("creative_id", String.valueOf(body.get("creative_id")));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mlClient.post("/decide/landing", req, Map.class);
            return ResponseEntity.ok(result == null ? Map.of("variant_id", variant.getId().toString()) : result);
        } catch (Exception e) {
            log.warn("Public decide failed for slug {}: {}", slug, e.getMessage());
            // Decide failure is non-fatal for the runner: it renders the slug's own variant.
            return ResponseEntity.ok(Map.of("variant_id", variant.getId().toString(), "decision_id", "", "fallback", true));
        }
    }

    /** Fixed one-minute window per key hash; returns false once the window exceeds DECIDE_PER_MINUTE. */
    boolean allowDecide(String keyHash) {
        long minute = System.currentTimeMillis() / 60_000L;
        long[] w = decideWindow.computeIfAbsent(keyHash, k -> new long[]{minute, 0});
        synchronized (w) {
            if (w[0] != minute) { w[0] = minute; w[1] = 0; }
            w[1]++;
            return w[1] <= DECIDE_PER_MINUTE;
        }
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
