package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CAPI Event Match Quality (EMQ) pre-check (roadmap 8-2; vision §19.2).
 * First-party checks only — actual EMQ score comes from Meta Events Manager after 24h.
 * Target: EMQ ≥ 7.0 for quality stages, ≥ 6.0 acceptable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CAPIEmqCheckService {

    private final JdbcTemplate jdbc;

    public Map<String, Object> runEmqPreCheck(UUID tenantId) {
        // Events fired vs sent to Meta via capi_events table
        List<Map<String, Object>> capiCounts = jdbc.queryForList("""
            SELECT cem.meta_event_name,
                   COUNT(ce.id)                         AS sent_to_meta,
                   COUNT(DISTINCT ce.session_id)        AS unique_sessions
            FROM capi_event_map cem
            LEFT JOIN capi_events ce
                ON ce.event_name = cem.meta_event_name
                AND ce.tenant_id = cem.tenant_id
                AND ce.created_at >= NOW() - INTERVAL '7 days'
            WHERE cem.tenant_id = ?
            GROUP BY cem.meta_event_name
            """, tenantId);

        // Dedup: events without event_id cannot be deduped in Meta Events Manager
        List<Map<String, Object>> dupCheck = jdbc.queryForList("""
            SELECT COUNT(*) AS total_events,
                   COUNT(event_id) AS with_event_id,
                   COUNT(*) - COUNT(event_id) AS missing_event_id
            FROM capi_events
            WHERE tenant_id = ? AND created_at >= NOW() - INTERVAL '7 days'
            """, tenantId);

        // Active pixel check
        List<Map<String, Object>> pixels = jdbc.queryForList("""
            SELECT pixel_id, status FROM meta_pixels
            WHERE tenant_id = ? AND status = 'ACTIVE'
            """, tenantId);

        List<String> warnings = new ArrayList<>();
        boolean passed = true;

        for (Map<String, Object> row : capiCounts) {
            long sent = row.get("sent_to_meta") != null
                ? ((Number) row.get("sent_to_meta")).longValue() : 0;
            if (sent == 0) {
                warnings.add("Event '" + row.get("meta_event_name") + "': 0 events sent to Meta in last 7 days");
            }
        }

        if (!dupCheck.isEmpty()) {
            long missing = dupCheck.get(0).get("missing_event_id") != null
                ? ((Number) dupCheck.get(0).get("missing_event_id")).longValue() : 0;
            if (missing > 0) {
                warnings.add(missing + " CAPI events missing event_id — deduplication may fail in Meta Events Manager");
            }
        }

        if (pixels.isEmpty()) {
            warnings.add("No active Meta pixel — CAPI events cannot be matched to pixel traffic");
            passed = false;
        }

        if (capiCounts.stream().allMatch(r ->
                ((Number) r.getOrDefault("sent_to_meta", 0)).longValue() == 0)) {
            passed = false;
        }

        return Map.of(
            "passed",          passed,
            "events_checked",  capiCounts,
            "warnings",        warnings,
            "pixel_count",     pixels.size(),
            "dedup_check",     dupCheck.isEmpty() ? Map.of() : dupCheck.get(0),
            "note",            "First-party pre-check only. Verify EMQ score in Meta Events Manager after 24h.",
            "emq_target",      "EMQ ≥ 7.0 for quality events, ≥ 6.0 acceptable"
        );
    }
}
