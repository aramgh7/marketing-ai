package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.service.MlServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Follow-up sequence management — Thompson Sampling bandit (vision §19.6 / arch §16.6).
 *
 * SECURITY: Auto-deploy variants WITHOUT marketer approval FORBIDDEN (§19.6).
 * All variants must be in lifecycle_state=APPROVED before going live.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/followup")
@RequiredArgsConstructor
public class FollowupController {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;

    /** Audit §3.4: this used the JWT subject (user id) as the tenant id; TenantContext holds the tenant claim. */
    private UUID currentTenant() {
        return UUID.fromString(TenantContext.get());
    }

    // ─── Variant CRUD ─────────────────────────────────────────────────────────

    @GetMapping("/variants")
    public ResponseEntity<List<Map<String, Object>>> getVariants() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, audience, cadence, channels, is_control,
                       lifecycle_state, approved_by, approved_at, alpha, beta
                FROM followup_variants
                WHERE tenant_id = ?
                ORDER BY created_at
                """, currentTenant());
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/variants")
    public ResponseEntity<Map<String, Object>> createVariant(@RequestBody Map<String, Object> body) {
        String name     = (String) body.get("name");
        String audience = (String) body.get("audience");
        if (name == null || audience == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "name and audience required"));
        }

        String cadence  = toJsonString(body.getOrDefault("cadence", List.of()));
        Object chObj    = body.getOrDefault("channels", List.of());
        String channels = chObj instanceof List<?> l
                ? "{" + String.join(",", l.stream().map(Object::toString).toList()) + "}"
                : "{}";
        boolean isControl = Boolean.TRUE.equals(body.get("is_control"));

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO followup_variants
                    (id, tenant_id, name, audience, cadence, channels, is_control, lifecycle_state)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'DRAFT')
                """, id, currentTenant(), name, audience, cadence, channels, isControl);

        return ResponseEntity.ok(Map.of("id", id.toString(), "lifecycle_state", "DRAFT"));
    }

    @PutMapping("/variants/{id}")
    public ResponseEntity<Map<String, Object>> updateVariant(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {

        // Only DRAFT variants can be edited (APPROVED requires re-review)
        int updated = jdbc.update("""
                UPDATE followup_variants
                SET name     = COALESCE(?, name),
                    cadence  = COALESCE(?::jsonb, cadence),
                    channels = COALESCE(?, channels)
                WHERE id = ? AND tenant_id = ? AND lifecycle_state = 'DRAFT'
                """,
                body.get("name"),
                body.containsKey("cadence") ? toJsonString(body.get("cadence")) : null,
                null,
                id, currentTenant());

        if (updated == 0) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    /** Approve a variant — sets lifecycle_state = APPROVED. */
    @PostMapping("/variants/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveVariant(@PathVariable UUID id) {
        UUID approver = currentTenant();
        int updated = jdbc.update("""
                UPDATE followup_variants
                SET lifecycle_state = 'APPROVED',
                    approved_by     = ?,
                    approved_at     = ?
                WHERE id = ? AND tenant_id = ? AND lifecycle_state = 'PENDING_REVIEW'
                """, approver, Instant.now(), id, currentTenant());

        if (updated == 0) return ResponseEntity.badRequest()
                .body(Map.of("error", "Variant not found or not in PENDING_REVIEW state"));
        log.info("Follow-up variant approved: id={} by={}", id, approver);
        return ResponseEntity.ok(Map.of("id", id.toString(), "lifecycle_state", "APPROVED"));
    }

    /** Submit variant for review (DRAFT → PENDING_REVIEW). */
    @PostMapping("/variants/{id}/submit")
    public ResponseEntity<Map<String, Object>> submitForReview(@PathVariable UUID id) {
        int updated = jdbc.update("""
                UPDATE followup_variants
                SET lifecycle_state = 'PENDING_REVIEW'
                WHERE id = ? AND tenant_id = ? AND lifecycle_state = 'DRAFT'
                """, id, currentTenant());
        if (updated == 0) return ResponseEntity.badRequest()
                .body(Map.of("error", "Variant not found or not in DRAFT state"));
        return ResponseEntity.ok(Map.of("id", id.toString(), "lifecycle_state", "PENDING_REVIEW"));
    }

    // ─── Assignment ───────────────────────────────────────────────────────────

    /**
     * Assign a follow-up variant to an appointment (via ML service Thompson decision).
     * Only assigns if no assignment exists yet (idempotent).
     */
    @PostMapping("/assign")
    public ResponseEntity<Map<String, Object>> assignFollowup(@RequestBody Map<String, Object> body) {
        UUID tenantId      = currentTenant();
        String apptId      = (String) body.get("appointment_id");
        String countryCode = (String) body.get("country_code");
        String ticketBucket= (String) body.get("ticket_bucket");

        if (apptId == null) return ResponseEntity.badRequest().body(Map.of("error", "appointment_id required"));

        // Check no existing assignment
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM followup_assignments WHERE appointment_id = ?::uuid",
                Integer.class, apptId);
        if (existing != null && existing > 0) {
            return ResponseEntity.ok(Map.of("status", "already_assigned"));
        }

        // Load eligible variants
        List<Map<String, Object>> variants = jdbc.queryForList("""
                SELECT id, name, alpha, beta, is_control, lifecycle_state
                FROM followup_variants
                WHERE tenant_id = ? AND (lifecycle_state = 'APPROVED' OR is_control = true)
                """, tenantId);

        if (variants.isEmpty()) {
            return ResponseEntity.ok(Map.of("status", "no_variants"));
        }

        // Delegate Thompson selection to ML service
        Map<String, Object> mlReq = Map.of(
                "tenant_id", tenantId.toString(),
                "appointment_id", apptId,
                "country_code", countryCode != null ? countryCode : "",
                "ticket_bucket", ticketBucket != null ? ticketBucket : "",
                "exploration_rate", 0.15,
                "variants", variants);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> mlResp = mlClient.post("/decide/followup", mlReq, Map.class);

            String variantId  = (String) mlResp.get("variant_id");
            String contextKey = (String) mlResp.get("context_key");

            UUID assignId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO followup_assignments
                        (id, tenant_id, appointment_id, variant_id, context_key, outcome)
                    VALUES (?, ?, ?::uuid, ?::uuid, ?, 'UNKNOWN')
                    ON CONFLICT (appointment_id) DO NOTHING
                    """, assignId, tenantId, apptId, variantId, contextKey);

            return ResponseEntity.ok(Map.of(
                    "assignment_id", assignId.toString(),
                    "variant_id", variantId,
                    "context_key", contextKey));
        } catch (Exception e) {
            log.warn("Follow-up assignment ML call failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Record outcome (SHOWED / NO_SHOW) for a follow-up assignment. */
    @PostMapping("/outcome")
    public ResponseEntity<Map<String, Object>> recordOutcome(@RequestBody Map<String, Object> body) {
        String apptId = (String) body.get("appointment_id");
        String outcome= (String) body.get("outcome");

        if (apptId == null || outcome == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "appointment_id and outcome required"));
        }
        if (!List.of("SHOWED", "NO_SHOW").contains(outcome)) {
            return ResponseEntity.badRequest().body(Map.of("error", "outcome must be SHOWED or NO_SHOW"));
        }

        // Single writer: FastAPI marks the assignment outcome AND updates followup_variants alpha/beta
        // in one transaction (audit 2.10 — previously nothing ever incremented alpha/beta).
        @SuppressWarnings("unchecked")
        Map<String, Object> result = mlClient.post("/decide/followup/outcome", Map.of(
                "tenant_id",      currentTenant().toString(),
                "appointment_id", apptId,
                "outcome",        outcome), Map.class);
        Map<String, Object> resp = new HashMap<>(Map.of("status", "recorded", "outcome", outcome));
        if (result != null) resp.putAll(result);
        return ResponseEntity.ok(resp);
    }

    // ─── Report ───────────────────────────────────────────────────────────────

    @GetMapping("/report")
    public ResponseEntity<List<Map<String, Object>>> getReport() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT fv.name, fv.is_control, fv.alpha, fv.beta,
                       COUNT(fa.id) as assignments,
                       SUM(CASE WHEN fa.outcome = 'SHOWED' THEN 1 ELSE 0 END) as showed_count,
                       SUM(CASE WHEN fa.outcome = 'NO_SHOW' THEN 1 ELSE 0 END) as no_show_count,
                       ROUND(fv.alpha / (fv.alpha + fv.beta), 4) as estimated_show_rate
                FROM followup_variants fv
                LEFT JOIN followup_assignments fa ON fv.id = fa.variant_id
                WHERE fv.tenant_id = ?
                GROUP BY fv.id, fv.name, fv.is_control, fv.alpha, fv.beta
                ORDER BY fv.is_control DESC, estimated_show_rate DESC
                """, currentTenant());
        return ResponseEntity.ok(rows);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String toJsonString(Object obj) {
        if (obj instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
