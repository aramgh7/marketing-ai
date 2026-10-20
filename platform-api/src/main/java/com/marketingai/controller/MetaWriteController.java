package com.marketingai.controller;

import com.marketingai.service.MetaWriteStageService;
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
 * Roadmap 9-19: Meta write policy — stage, readiness, thresholds, overrides and the decision ledger.
 *   GET  /api/v1/tenants/me/meta-write                 stage + readiness for the next stage + thresholds
 *   PUT  /api/v1/tenants/me/meta-write/stage           {stage, override_reason?}
 *   PUT  /api/v1/tenants/me/meta-write/policy          thresholds (partial)
 *   POST /api/v1/tenants/me/meta-write/overrides       {action|'*', target_id?, reason, ttl_minutes?}
 *   GET  /api/v1/tenants/me/meta-write/decisions?limit=
 */
@RestController
@RequestMapping("/tenants/me/meta-write")
@RequiredArgsConstructor
public class MetaWriteController {

    private final MetaWriteStageService stageService;
    private final JdbcTemplate jdbc;

    @GetMapping
    public Map<String, Object> status(@RequestAttribute("tenantId") UUID tenantId) {
        return stageService.status(tenantId);
    }

    @PutMapping("/stage")
    public Map<String, Object> setStage(@RequestAttribute("tenantId") UUID tenantId,
                                        @RequestAttribute(value = "userId", required = false) UUID userId,
                                        @RequestBody Map<String, String> body) {
        return stageService.setStage(tenantId, body.get("stage"), body.get("override_reason"), userId);
    }

    @PutMapping("/policy")
    public ResponseEntity<Void> setPolicy(@RequestAttribute("tenantId") UUID tenantId,
                                          @RequestBody Map<String, Object> thresholds) {
        stageService.updatePolicy(tenantId, thresholds);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/overrides")
    public ResponseEntity<Map<String, Object>> override(@RequestAttribute("tenantId") UUID tenantId,
                                                        @RequestAttribute(value = "userId", required = false) UUID userId,
                                                        @RequestBody Map<String, Object> body) {
        String action = String.valueOf(body.getOrDefault("action", "*")).toUpperCase();
        String reason = body.get("reason") == null ? "" : body.get("reason").toString().trim();
        if (reason.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is mandatory (≥ 10 characters)");
        }
        int ttl = body.get("ttl_minutes") instanceof Number n ? Math.max(1, Math.min(n.intValue(), 240)) : 30;
        String target = body.get("target_id") == null ? null : body.get("target_id").toString();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO meta_write_overrides (id, tenant_id, action, target_id, reason, created_by, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + (? * INTERVAL '1 minute'))
                """, id, tenantId, action, target, reason, userId, ttl);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", id, "action", action, "ttl_minutes", ttl, "note", "one-shot: consumed by the first allowed write"));
    }

    @GetMapping("/decisions")
    public List<Map<String, Object>> decisions(@RequestAttribute("tenantId") UUID tenantId,
                                               @RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT id, action, action_class, target_id, initiator, stage, autonomy_level, verdict, reason,
                       budget_delta_pct, override_id, created_at::text AS created_at
                FROM meta_write_decisions WHERE tenant_id = ?
                ORDER BY created_at DESC LIMIT ?
                """, tenantId, Math.max(1, Math.min(limit, 500)));
    }
}
