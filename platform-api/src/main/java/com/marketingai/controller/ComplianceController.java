package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.service.ComplianceService;
import com.marketingai.service.MlServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * Compliance API — generic text checks + securities rulebases (vision §19.5).
 */
@RestController
@RequestMapping("/compliance")
@RequiredArgsConstructor
public class ComplianceController {

    private final ComplianceService complianceService;
    private final MlServiceClient mlClient;
    private final JdbcTemplate jdbc;

    /** Audit §3.4: this used the JWT subject (user id) as the tenant id; TenantContext holds the tenant claim. */
    private UUID currentTenant() {
        return UUID.fromString(TenantContext.get());
    }

    // ─── Generic text compliance ─────────────────────────────────────────────

    @PostMapping("/check")
    public ResponseEntity<ComplianceCheck> check(@RequestBody Map<String, Object> body) {
        String entityType = (String) body.get("entity_type");
        UUID entityId = UUID.fromString((String) body.get("entity_id"));
        String text = (String) body.get("text");
        return ResponseEntity.ok(complianceService.runTextCheck(entityType, entityId, text));
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<List<ComplianceCheck>> getChecks(@PathVariable UUID entityId) {
        return ResponseEntity.ok(complianceService.getChecks(entityId));
    }

    @PutMapping("/{id}/override")
    public ResponseEntity<ComplianceCheck> override(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID overriderId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());
        String reason = body.get("reason");
        return ResponseEntity.ok(complianceService.override(id, overriderId, reason));
    }

    // ─── Rulebases (platform-wide) ───────────────────────────────────────────

    @GetMapping("/rulebases")
    public ResponseEntity<List<Map<String, Object>>> getRulebases() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rulebase_key, description, rules, updated_at FROM compliance_rulebases ORDER BY rulebase_key");
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/rulebases/{key}")
    public ResponseEntity<Map<String, Object>> getRulebase(@PathVariable String key) {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT rulebase_key, description, rules, updated_at FROM compliance_rulebases WHERE rulebase_key = ?", key);
            return ResponseEntity.ok(row);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ─── Approved phrases ─────────────────────────────────────────────────────

    @GetMapping("/approved-phrases")
    public ResponseEntity<List<Map<String, Object>>> getApprovedPhrases() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, phrase, context, approved_at FROM approved_phrases WHERE tenant_id = ? ORDER BY approved_at DESC",
                currentTenant());
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/approved-phrases")
    public ResponseEntity<Map<String, Object>> addApprovedPhrase(@RequestBody Map<String, String> body) {
        String phrase = body.get("phrase");
        if (phrase == null || phrase.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "phrase required"));
        }
        String context = body.getOrDefault("context", "");
        UUID approvedBy = currentTenant();

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO approved_phrases (id, tenant_id, phrase, context, approved_by, approved_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, phrase) DO UPDATE
                SET context = EXCLUDED.context,
                    approved_by = EXCLUDED.approved_by,
                    approved_at = EXCLUDED.approved_at
            """, id, currentTenant(), phrase, context, approvedBy, Instant.now());
        return ResponseEntity.ok(Map.of("id", id.toString(), "status", "saved"));
    }

    // ─── Pre-check (proxy to ML service) ─────────────────────────────────────

    /**
     * Run text against one or more named rulebases via ML service.
     * Loads rulebase rules from DB, proxies to /compliance/check in ML service.
     */
    @PostMapping("/pre-check")
    public ResponseEntity<Map<String, Object>> preCheck(@RequestBody Map<String, Object> body) {
        String text = (String) body.get("text");
        @SuppressWarnings("unchecked")
        List<String> rulebases = (List<String>) body.getOrDefault("rulebases", List.of("META_FINANCIAL", "META_PERSONAL_ATTR"));

        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text required"));
        }

        Map<String, Object> rules = new LinkedHashMap<>();
        for (String rk : rulebases) {
            try {
                String json = jdbc.queryForObject(
                        "SELECT rules FROM compliance_rulebases WHERE rulebase_key = ?",
                        String.class, rk);
                if (json != null) rules.put(rk, json);
            } catch (Exception ignored) {}
        }

        Map<String, Object> mlReq = Map.of("text", text, "rulebases", rules);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mlClient.post("/compliance/check", mlReq, Map.class);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "ML service unavailable", "detail", e.getMessage()));
        }
    }
}
