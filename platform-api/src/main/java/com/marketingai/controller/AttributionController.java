package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@RestController
@RequestMapping("/attribution")
@RequiredArgsConstructor
@Slf4j
public class AttributionController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @PostMapping("/touchpoint")
    public ResponseEntity<Map<String, Object>> recordTouchpoint(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        UUID id        = UUID.randomUUID();
        UUID sessionId = body.get("session_id") != null
            ? UUID.fromString((String) body.get("session_id")) : null;

        int count = 0;
        if (sessionId != null) {
            Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM touchpoints WHERE session_id = ? AND tenant_id = ?",
                Integer.class, sessionId, tenantId);
            count = c != null ? c : 0;
        }

        jdbc.update("""
            INSERT INTO touchpoints
                (id, tenant_id, session_id, source, medium, campaign,
                 channel, page_url, is_first_touch)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, tenantId, sessionId,
            body.get("source"), body.get("medium"), body.get("campaign"),
            classifyChannel((String) body.get("source"), (String) body.get("medium")),
            body.get("page_url"), count == 0);

        return ResponseEntity.ok(Map.of("touchpoint_id", id));
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> sessionAttribution(
            @PathVariable UUID sessionId,
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "last_touch,linear,time_decay") String models) {

        List<Map<String, Object>> touchpoints = jdbc.queryForList("""
            SELECT id, source, medium, campaign, channel, touched_at, page_url
            FROM touchpoints
            WHERE session_id = ? AND tenant_id = ?
            ORDER BY touched_at ASC
            """, sessionId, tenantId);

        if (touchpoints.isEmpty()) {
            return ResponseEntity.ok(Map.of("touchpoints", List.of(), "attribution", Map.of()));
        }

        try {
            Map<String, Object> req = Map.of(
                "touchpoints", touchpoints,
                "models",      List.of(models.split(","))
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/attribution/run", req, Map.class);
            return ResponseEntity.ok(Map.of(
                "touchpoints", touchpoints,
                "attribution", result != null ? result : Map.of()
            ));
        } catch (Exception e) {
            log.warn("Attribution call failed: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("touchpoints", touchpoints));
        }
    }

    @GetMapping("/channel-report")
    public ResponseEntity<List<Map<String, Object>>> channelReport(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT ac.model, tp.channel,
                   COUNT(DISTINCT ac.session_id) AS conversions,
                   ROUND(SUM(ac.credit)::numeric, 2) AS total_credit
            FROM attribution_credits ac
            JOIN touchpoints tp ON tp.id = ac.touchpoint_id
            WHERE ac.tenant_id = ? AND ac.computed_at >= NOW() - (? || ' days')::interval
            GROUP BY ac.model, tp.channel
            ORDER BY ac.model, total_credit DESC
            """, tenantId, String.valueOf(days)));
    }

    private String classifyChannel(String source, String medium) {
        if (source == null && medium == null) return "DIRECT";
        String s = source  != null ? source.toLowerCase()  : "";
        String m = medium != null ? medium.toLowerCase() : "";
        if (s.contains("facebook") || s.contains("meta") || s.contains("instagram"))
            return "PAID_SOCIAL";
        if (s.contains("google") || s.contains("bing") || m.contains("cpc"))
            return "PAID_SEARCH";
        if (m.contains("email") || s.contains("email"))
            return "EMAIL";
        if (m.contains("referral") || s.contains("referral"))
            return "REFERRAL";
        if (m.contains("organic"))
            return "ORGANIC";
        return "DIRECT";
    }
}
