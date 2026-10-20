package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.service.FunnelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Funnel as Configuration REST API (vision §19.3 / arch §16.3).
 */
@RestController
@RequestMapping("/api/v1/funnel")
@RequiredArgsConstructor
public class FunnelController {

    private final FunnelService funnelService;
    private final JdbcTemplate  jdbc;

    /** Audit §3.4: this used the JWT subject (user id) as the tenant id; TenantContext holds the tenant claim. */
    private UUID currentTenant() {
        return UUID.fromString(TenantContext.get());
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getStages() {
        return ResponseEntity.ok(funnelService.getStages(currentTenant()));
    }

    @PutMapping("/seed/b2b")
    public ResponseEntity<Map<String, String>> seedB2B() {
        funnelService.seedB2BTemplate(currentTenant());
        return ResponseEntity.ok(Map.of("status", "seeded", "template", "b2b"));
    }

    @PutMapping("/seed/investor")
    public ResponseEntity<Map<String, String>> seedInvestor() {
        funnelService.seedInvestorFunnel(currentTenant());
        return ResponseEntity.ok(Map.of("status", "seeded", "template", "investor"));
    }

    /** GET /api/v1/funnel/me — funnel stages + CAPI map for current tenant (admin UI 8-1). */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> getMyFunnel() {
        UUID tenantId = currentTenant();
        List<Map<String, Object>> stages = jdbc.queryForList("""
            SELECT fs.stage_key, fs.stage_label, fs.ordinal, fs.is_quality_key,
                   fs.reward_weight, fs.micro_reward_weight,
                   cem.meta_event_name AS capi_event
            FROM funnel_stages fs
            LEFT JOIN capi_event_map cem
                ON cem.tenant_id = fs.tenant_id AND cem.stage_key = fs.stage_key
            WHERE fs.tenant_id = ?
            ORDER BY fs.ordinal
            """, tenantId);
        return ResponseEntity.ok(Map.of("stages", stages, "tenant_id", tenantId));
    }

    @PostMapping("/stages")
    public ResponseEntity<Map<String, String>> upsertStage(@RequestBody Map<String, Object> stage) {
        if (!stage.containsKey("stage_key") || !stage.containsKey("ordinal")) {
            return ResponseEntity.badRequest().body(Map.of("error", "stage_key and ordinal required"));
        }
        funnelService.upsertStage(currentTenant(), stage);
        return ResponseEntity.ok(Map.of("status", "saved", "stage_key", (String) stage.get("stage_key")));
    }
}
