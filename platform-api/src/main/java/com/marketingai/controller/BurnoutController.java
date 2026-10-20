package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Creative Burnout API (Phase 6-1, §8.1).
 * Delegates computation to ml-service /burnout/score; persists events in DB.
 */
@Slf4j
@RestController
@RequestMapping("/burnout")
@RequiredArgsConstructor
public class BurnoutController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    // ─── GET /summary ────────────────────────────────────────────────────────

    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, creative_id, ad_set_id, frequency, ctr_current, ctr_baseline,
                       burnout_score, stage, signals, resolved, detected_at
                FROM creative_burnout_events
                WHERE tenant_id = ? AND resolved = FALSE
                ORDER BY burnout_score DESC, detected_at DESC
                LIMIT 50
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─── POST /{eventId}/resolve ──────────────────────────────────────────────

    @PostMapping("/{eventId}/resolve")
    public ResponseEntity<Void> resolve(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID eventId) {

        int updated = jdbc.update("""
                UPDATE creative_burnout_events
                SET resolved = TRUE, resolved_at = now()
                WHERE id = ? AND tenant_id = ?
                """, eventId, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Burnout event not found");
        }
        return ResponseEntity.noContent().build();
    }

    // ─── POST /scan ───────────────────────────────────────────────────────────
    // Triggers burnout scan for all active creatives of the tenant via ml-service.

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody(required = false) Map<String, Object> body) {

        List<Map<String, Object>> insights = jdbc.queryForList("""
                SELECT ad_set_id,
                       AVG(frequency)                          AS frequency,
                       AVG(ctr)                               AS ctr_current,
                       MIN(CASE WHEN rn = 1 THEN ctr END)    AS ctr_baseline
                FROM (
                    SELECT ad_set_id, frequency, ctr,
                           ROW_NUMBER() OVER (PARTITION BY ad_set_id ORDER BY recorded_at ASC) AS rn
                    FROM meta_insights_history
                    WHERE tenant_id = ?
                      AND recorded_at > now() - interval '14 days'
                ) sub
                GROUP BY ad_set_id
                LIMIT 100
                """, tenantId);

        int detected = 0;
        for (Map<String, Object> row : insights) {
            String adSetId      = (String) row.get("ad_set_id");
            double frequency    = toDouble(row.get("frequency"));
            double ctrCurrent   = toDouble(row.get("ctr_current"));
            double ctrBaseline  = toDouble(row.get("ctr_baseline"));

            if (ctrBaseline <= 0) continue;

            Map<String, Object> mlReq = Map.of(
                    "frequency",    frequency,
                    "ctr_current",  ctrCurrent,
                    "ctr_baseline", ctrBaseline
            );

            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = restTemplate.postForObject(
                        appProperties.getMlServiceUrl() + "/burnout/score", mlReq, Map.class);

                if (result != null && !"none".equals(result.get("stage"))) {
                    jdbc.update("""
                            INSERT INTO creative_burnout_events
                                (tenant_id, creative_id, ad_set_id, frequency,
                                 ctr_current, ctr_baseline, burnout_score, stage, signals)
                            VALUES (?, gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?::jsonb)
                            ON CONFLICT DO NOTHING
                            """,
                            tenantId, adSetId, frequency, ctrCurrent, ctrBaseline,
                            result.get("score"), result.get("stage"),
                            result.getOrDefault("signals", "[]").toString());
                    detected++;
                }
            } catch (Exception e) {
                log.warn("Burnout scan failed for ad_set {}: {}", adSetId, e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of("scanned", insights.size(), "detected", detected));
    }

    private double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
