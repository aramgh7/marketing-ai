package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 8-10: External Signals Role per Funnel (vision §19.13; arch §16.10).
 * Manages funnel_signal_config on the tenant and funnel_calendar_events.
 */
@RestController
@RequestMapping("/api/v1/funnel-signals")
@RequiredArgsConstructor
@Slf4j
public class FunnelSignalController {

    private final JdbcTemplate  jdbc;
    private final RestTemplate  restTemplate;

    // ── Signal Config ──────────────────────────────────────────────────────────

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig(
            @RequestAttribute("tenantId") UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT funnel_signal_config FROM tenants WHERE id = ?", tenantId);
        if (rows.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("funnel_signal_config", rows.get(0).get("funnel_signal_config")));
    }

    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        Object config = body.get("funnel_signal_config");
        if (config == null) return ResponseEntity.badRequest().build();

        String json = config instanceof String s ? s : toJson(config);
        jdbc.update(
            "UPDATE tenants SET funnel_signal_config = ?::jsonb WHERE id = ?",
            json, tenantId);
        return ResponseEntity.ok(Map.of("updated", true));
    }

    // ── Calendar Events ────────────────────────────────────────────────────────

    @GetMapping("/calendar")
    public ResponseEntity<List<Map<String, Object>>> listCalendar(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "investor") String funnelKey) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id, funnel_key, event_type, event_name, event_date,
                   jurisdiction, metadata, created_at,
                   (event_date - CURRENT_DATE) AS days_until
            FROM funnel_calendar_events
            WHERE tenant_id = ? AND funnel_key = ?
            ORDER BY event_date
            """, tenantId, funnelKey));
    }

    @PostMapping("/calendar")
    public ResponseEntity<Map<String, Object>> addCalendarEvent(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        String funnelKey   = (String) body.getOrDefault("funnel_key",   "investor");
        String eventType   = (String) body.getOrDefault("event_type",   "regulatory");
        String eventName   = (String) body.getOrDefault("event_name",   "");
        String eventDate   = (String) body.getOrDefault("event_date",   "");
        String jurisdiction = (String) body.get("jurisdiction");
        String meta        = body.get("metadata") != null ? toJson(body.get("metadata")) : "{}";

        if (eventName.isBlank() || eventDate.isBlank())
            return ResponseEntity.badRequest().build();

        jdbc.update("""
            INSERT INTO funnel_calendar_events
                (id, tenant_id, funnel_key, event_type, event_name, event_date, jurisdiction, metadata)
            VALUES (?, ?, ?, ?, ?, ?::date, ?, ?::jsonb)
            """, id, tenantId, funnelKey, eventType, eventName, eventDate, jurisdiction, meta);

        return ResponseEntity.ok(Map.of("id", id.toString(), "created", true));
    }

    @DeleteMapping("/calendar/{eventId}")
    public ResponseEntity<Map<String, Object>> deleteCalendarEvent(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID eventId) {
        int rows = jdbc.update(
            "DELETE FROM funnel_calendar_events WHERE id = ? AND tenant_id = ?",
            eventId, tenantId);
        return ResponseEntity.ok(Map.of("deleted", rows > 0));
    }

    // ── Narrative Context (proxies to ml-service) ──────────────────────────────

    @GetMapping("/narrative-context")
    public ResponseEntity<Map<String, Object>> narrativeContext(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "investor") String funnelKey) {

        List<Map<String, Object>> calRows = jdbc.queryForList("""
            SELECT event_type, event_name,
                   (event_date - CURRENT_DATE) AS days_until
            FROM funnel_calendar_events
            WHERE tenant_id = ? AND funnel_key = ?
            AND event_date >= CURRENT_DATE
            ORDER BY event_date
            LIMIT 10
            """, tenantId, funnelKey);

        List<Map<String, Object>> configRows = jdbc.queryForList(
            "SELECT funnel_signal_config FROM tenants WHERE id = ?", tenantId);
        List<String> newsTags = List.of();
        if (!configRows.isEmpty()) {
            Object cfg = configRows.get(0).get("funnel_signal_config");
            newsTags = extractNewsTags(cfg, funnelKey);
        }

        try {
            Map<String, Object> req = new HashMap<>();
            req.put("raw_signals",     List.of());  // live signals fetched by ExternalSignalsScheduler
            req.put("news_tags",       newsTags);
            req.put("calendar_events", calRows);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.postForObject(
                "http://ml-service:8001/signals/narrative-context", req, Map.class);
            return ResponseEntity.ok(resp != null ? resp : Map.of());
        } catch (Exception e) {
            log.warn("ml-service narrative-context unavailable: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("context_lines", List.of()));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> extractNewsTags(Object configObj, String funnelKey) {
        try {
            if (configObj instanceof Map<?,?> map) {
                Object funnelCfg = map.get(funnelKey);
                if (funnelCfg instanceof Map<?,?> fc) {
                    Object tags = fc.get("news_tags");
                    if (tags instanceof List<?> list) {
                        return (List<String>) list;
                    }
                }
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
