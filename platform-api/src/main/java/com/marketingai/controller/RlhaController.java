package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// RlhaController uses TenantContext.get() for tenant isolation

@RestController
@RequestMapping("/rlha")
@RequiredArgsConstructor
public class RlhaController {

    private final JdbcTemplate jdbc;

    @GetMapping("/corrections")
    public ResponseEntity<List<Map<String, Object>>> corrections(
            @RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, generation_id, generated_text, approved_text,
                       delta_tags, use_as_few_shot, edited_by, created_at
                FROM rlha_corrections
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, Math.min(limit, 200));
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        UUID tenantId = UUID.fromString(TenantContext.get());
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                    COUNT(*) AS total_corrections,
                    COUNT(*) FILTER (WHERE use_as_few_shot) AS few_shot_count,
                    COUNT(DISTINCT edited_by) AS unique_editors
                FROM rlha_corrections
                WHERE tenant_id = ?
                """, tenantId);
        return ResponseEntity.ok(row);
    }
}
