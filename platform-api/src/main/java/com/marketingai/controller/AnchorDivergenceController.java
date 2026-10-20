package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anchor Divergence API (3-14).
 * GET /api/v1/campaigns/{id}/anchor-divergence
 */
@RestController
@RequestMapping("/campaigns")
@RequiredArgsConstructor
public class AnchorDivergenceController {

    private final JdbcTemplate jdbc;

    @GetMapping("/{id}/anchor-divergence")
    public List<Map<String, Object>> getAnchorDivergence(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "CTR") String metric,
            @RequestParam(defaultValue = "24") int windowHours) {
        UUID tenantId = currentTenant();

        return jdbc.queryForList("""
                SELECT id, metric, p_anchor_drop, p_test_drop, verdict,
                       external_ctx, created_at
                FROM anchor_divergence_log
                WHERE tenant_id = ?
                  AND campaign_id = ?
                  AND created_at > now() - (? || ' hours')::interval
                ORDER BY created_at DESC
                LIMIT 50
                """, tenantId, id, windowHours);
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
