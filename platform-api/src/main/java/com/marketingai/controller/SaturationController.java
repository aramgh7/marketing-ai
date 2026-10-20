package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Audience Saturation API (Phase 6-2, §8.2).
 * Records reach/frequency snapshots and proxies saturation scoring to ml-service.
 */
@Slf4j
@RestController
@RequestMapping("/saturation")
@RequiredArgsConstructor
public class SaturationController {

    private final JdbcTemplate jdbc;

    // ─── GET /summary ─────────────────────────────────────────────────────────

    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ad_set_id,
                       MAX(recorded_at)   AS latest_at,
                       MAX(frequency)     AS frequency_max,
                       MIN(frequency)     AS frequency_min,
                       AVG(ctr)           AS ctr_avg,
                       SUM(spend)         AS total_spend
                FROM meta_insights_history
                WHERE tenant_id = ?
                  AND recorded_at > now() - interval '30 days'
                GROUP BY ad_set_id
                ORDER BY frequency_max DESC
                LIMIT 50
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─── POST /record ─────────────────────────────────────────────────────────
    // Record a Meta insights snapshot for saturation tracking.

    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> record(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String adSetId     = (String) body.get("ad_set_id");
        if (adSetId == null || adSetId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ad_set_id required"));
        }

        jdbc.update("""
                INSERT INTO meta_insights_history
                    (tenant_id, ad_set_id, reach, impressions, spend, frequency, clicks, ctr)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (ad_set_id, recorded_at) DO NOTHING
                """,
                tenantId,
                adSetId,
                toLong(body.get("reach")),
                toLong(body.get("impressions")),
                toDouble(body.get("spend")),
                toDouble(body.get("frequency")),
                toLong(body.get("clicks")),
                body.containsKey("ctr") ? toDouble(body.get("ctr")) : null
        );

        return ResponseEntity.ok(Map.of("recorded", true));
    }

    private double toDouble(Object v) { return v instanceof Number n ? n.doubleValue() : 0.0; }
    private long   toLong(Object v)   { return v instanceof Number n ? n.longValue()   : 0L;  }
}
