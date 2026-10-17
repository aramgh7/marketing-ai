package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Micro-conversion funnel map per landing block (vision §7.2).
 * Tracks BLOCK_VIEW → CTA_CLICK → FORM_START → FORM_SUBMIT per block.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/micro-conversion")
@RequiredArgsConstructor
public class MicroConversionController {

    private static final Set<String> VALID_TYPES =
            Set.of("BLOCK_VIEW", "CTA_CLICK", "FORM_START", "FORM_SUBMIT");

    private final JdbcTemplate jdbc;

    /** Batch ingest micro-conversion events. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>)
                body.getOrDefault("events", List.of());

        int inserted = 0;
        for (Map<String, Object> ev : events) {
            String eventType = String.valueOf(ev.getOrDefault("event_type", ""));
            if (!VALID_TYPES.contains(eventType)) continue;

            String blockId = String.valueOf(ev.getOrDefault("block_id", ""));
            if (blockId.isBlank()) continue;

            Object sessionIdRaw = ev.get("session_id");
            UUID sessionId = sessionIdRaw != null ? UUID.fromString(sessionIdRaw.toString()) : null;

            Object landingIdRaw = ev.get("landing_id");
            UUID landingId = landingIdRaw != null ? UUID.fromString(landingIdRaw.toString()) : null;

            String blockEventId = ev.containsKey("block_event_id")
                    ? ev.get("block_event_id").toString() : null;

            jdbc.update("""
                    INSERT INTO micro_conversion_events
                        (id, tenant_id, session_id, landing_id, block_id, event_type, block_event_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(), tenantId, sessionId, landingId, blockId, eventType, blockEventId);
            inserted++;
        }

        return ResponseEntity.ok(Map.of("inserted", inserted));
    }

    /** Funnel report: BLOCK_VIEW → CTA_CLICK → FORM_START → FORM_SUBMIT per block. */
    @GetMapping("/funnel")
    public ResponseEntity<List<Map<String, Object>>> funnel(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(required = false) UUID landingId) {

        String sql;
        Object[] args;

        if (landingId != null) {
            sql = """
                    SELECT block_id,
                           COUNT(*) FILTER (WHERE event_type = 'BLOCK_VIEW')   AS block_views,
                           COUNT(*) FILTER (WHERE event_type = 'CTA_CLICK')    AS cta_clicks,
                           COUNT(*) FILTER (WHERE event_type = 'FORM_START')   AS form_starts,
                           COUNT(*) FILTER (WHERE event_type = 'FORM_SUBMIT')  AS form_submits
                    FROM micro_conversion_events
                    WHERE tenant_id = ? AND landing_id = ?
                    GROUP BY block_id
                    ORDER BY block_views DESC
                    """;
            args = new Object[]{tenantId, landingId};
        } else {
            sql = """
                    SELECT block_id,
                           COUNT(*) FILTER (WHERE event_type = 'BLOCK_VIEW')   AS block_views,
                           COUNT(*) FILTER (WHERE event_type = 'CTA_CLICK')    AS cta_clicks,
                           COUNT(*) FILTER (WHERE event_type = 'FORM_START')   AS form_starts,
                           COUNT(*) FILTER (WHERE event_type = 'FORM_SUBMIT')  AS form_submits
                    FROM micro_conversion_events
                    WHERE tenant_id = ?
                    GROUP BY block_id
                    ORDER BY block_views DESC
                    """;
            args = new Object[]{tenantId};
        }

        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);

        // Enrich with conversion rates
        for (Map<String, Object> row : rows) {
            long views  = toLong(row.get("block_views"));
            long clicks = toLong(row.get("cta_clicks"));
            long starts = toLong(row.get("form_starts"));
            long submits = toLong(row.get("form_submits"));
            row.put("view_to_click_rate",   views  > 0 ? round((double) clicks  / views, 4)  : null);
            row.put("click_to_start_rate",  clicks > 0 ? round((double) starts  / clicks, 4) : null);
            row.put("start_to_submit_rate", starts > 0 ? round((double) submits / starts, 4) : null);
        }

        return ResponseEntity.ok(rows);
    }

    private long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    private double round(double v, int decimals) {
        double scale = Math.pow(10, decimals);
        return Math.round(v * scale) / scale;
    }
}
