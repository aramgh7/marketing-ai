package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.service.RlhaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RLHA (vision §13.3; roadmap 2-10 + 9-20).
 *   GET   /api/v1/rlha/corrections?limit=&content_type=
 *   POST  /api/v1/rlha/corrections              manual pair {content_type, generated_text, approved_text, reason}
 *   PATCH /api/v1/rlha/corrections/{id}         {use_as_few_shot, edit_reason}
 *   GET   /api/v1/rlha/metrics                  accepted-without-edits rate, weekly trend, render feedback
 *   GET   /api/v1/rlha/render-feedback
 */
@RestController
@RequestMapping("/rlha")
@RequiredArgsConstructor
public class RlhaController {

    private final JdbcTemplate jdbc;
    private final RlhaService rlhaService;

    @GetMapping("/corrections")
    public ResponseEntity<List<Map<String, Object>>> corrections(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String content_type) {
        UUID tenantId = tenant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, generation_id, generated_text, approved_text, delta_tags, use_as_few_shot,
                       edited_by, content_type, edit_reason, entity_type, entity_id, context::text AS context,
                       created_at::text AS created_at
                FROM rlha_corrections
                WHERE tenant_id = ? AND (? IS NULL OR content_type = ?)
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, content_type, content_type, Math.min(Math.max(limit, 1), 200));
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/corrections")
    public ResponseEntity<Map<String, Object>> add(@RequestAttribute(value = "userId", required = false) UUID userId,
                                                   @RequestBody Map<String, String> body) {
        String generated = body.getOrDefault("generated_text", "").trim();
        String approved = body.getOrDefault("approved_text", "").trim();
        if (generated.length() < 3 || approved.length() < 3 || generated.equals(approved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "generated_text and approved_text must differ and be non-trivial");
        }
        UUID id = rlhaService.addManualCorrection(tenant(), userId, body.getOrDefault("content_type", "landing_block"),
                generated, approved, body.get("reason"));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PatchMapping("/corrections/{id}")
    public ResponseEntity<Void> patch(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID tenantId = tenant();
        if (body.containsKey("use_as_few_shot")) {
            jdbc.update("UPDATE rlha_corrections SET use_as_few_shot = ? WHERE id = ? AND tenant_id = ?",
                    Boolean.TRUE.equals(body.get("use_as_few_shot")), id, tenantId);
        }
        if (body.get("edit_reason") instanceof String r && RlhaService.EDIT_REASONS.contains(r.toUpperCase())) {
            jdbc.update("UPDATE rlha_corrections SET edit_reason = ? WHERE id = ? AND tenant_id = ?", r.toUpperCase(), id, tenantId);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        return ResponseEntity.ok(rlhaService.metrics(tenant()));
    }

    @GetMapping("/render-feedback")
    public List<Map<String, Object>> renderFeedback(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT id, render_id, decision, reason, note, decided_by, created_at::text AS created_at
                FROM render_feedback WHERE tenant_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, tenant(), Math.min(Math.max(limit, 1), 200));
    }

    private static UUID tenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
