package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Circuit Breaker recovery diagnosis and batch resume (3-16).
 * Automatically determines the cause of a CB event and suggests resume steps.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryDiagnosisService {

    private final JdbcTemplate jdbc;
    private final AutonomyService autonomyService;

    /**
     * Diagnose a circuit breaker event.
     * Reads CB event + recent site_health_incidents + fraud scores to determine cause.
     * Sets diagnosis column on the event row.
     * Returns {diagnosis, confidence, resume_steps, evidence}.
     */
    public Map<String, Object> diagnose(UUID tenantId, UUID eventId) {
        Map<String, Object> cbEvent = jdbc.queryForMap(
                "SELECT * FROM circuit_breaker_events WHERE id = ? AND tenant_id = ?",
                eventId, tenantId);

        String triggerReason = (String) cbEvent.getOrDefault("trigger_reason", "");
        String level = (String) cbEvent.getOrDefault("level", "L1");

        // Check site health
        List<Map<String, Object>> openIncidents = jdbc.queryForList("""
                SELECT id FROM site_health_incidents
                WHERE tenant_id = ? AND resolved_at IS NULL
                """, tenantId);

        // Check fraud
        Map<String, Object> fraudRow = null;
        try {
            fraudRow = jdbc.queryForMap("""
                    SELECT COUNT(*) total,
                           SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END) fraud_count
                    FROM sessions
                    WHERE tenant_id = ? AND created_at > now() - interval '2 hours'
                    """, tenantId);
        } catch (Exception e) {
            log.debug("No sessions data for diagnosis: {}", e.getMessage());
        }

        String diagnosis = "NONE";
        double confidence = 0.5;
        List<String> resumeSteps = List.of("Investigate trigger_reason manually.", "Check Telegram alerts for more context.");
        List<String> evidence = new java.util.ArrayList<>();

        if (!openIncidents.isEmpty()) {
            diagnosis = "TECHNICAL";
            confidence = 0.90;
            evidence.add("site_health_incident_open: " + openIncidents.size() + " incidents");
            resumeSteps = List.of(
                    "Resolve site_health_incidents for this tenant.",
                    "Confirm landing pages are responding normally.",
                    "Restart affected ad sets after resolution."
            );
        } else if (fraudRow != null) {
            long total = toLong(fraudRow.get("total"));
            long fraudCount = toLong(fraudRow.get("fraud_count"));
            double fraudRate = total > 0 ? (double) fraudCount / total : 0.0;
            if (fraudRate >= 0.20) {
                diagnosis = "FRAUD";
                confidence = 0.85;
                evidence.add(String.format("fraud_rate=%.1f%% in last 2h", fraudRate * 100));
                resumeSteps = List.of(
                        "Review traffic sources — likely bot or click farm activity.",
                        "Block suspicious IP ranges in Meta Ads Manager.",
                        "Consider excluding affected placements.",
                        "Re-run fraud_l3 scan after mitigation."
                );
            } else if (triggerReason != null && triggerReason.contains("CPM")) {
                diagnosis = "MARKET";
                confidence = 0.75;
                evidence.add("CPM spike in trigger_reason");
                resumeSteps = List.of(
                        "Wait for CPM to normalise (usually 24-48h).",
                        "Check calendar for market events (holidays, competitor launches).",
                        "Resume with conservative daily budget cap."
                );
            } else if (triggerReason != null && (triggerReason.contains("CPA") || triggerReason.contains("ROAS"))) {
                diagnosis = "CREATIVE";
                confidence = 0.70;
                evidence.add("CPA/ROAS trigger with no fraud or site issues");
                resumeSteps = List.of(
                        "Refresh top creatives — run new hook variants.",
                        "Archive creatives with frequency > 4.",
                        "Review anchor divergence report for market vs creative signal."
                );
            }
        }

        // Persist diagnosis (both columns are JSONB)
        try {
            String diagJson = "\"" + diagnosis + "\"";
            String stepsJson = "[" + resumeSteps.stream()
                    .map(s -> "\"" + s.replace("\"", "\\\"") + "\"")
                    .collect(java.util.stream.Collectors.joining(",")) + "]";
            jdbc.update("""
                    UPDATE circuit_breaker_events
                    SET diagnosis = ?::jsonb, resume_steps = ?::jsonb
                    WHERE id = ?
                    """, diagJson, stepsJson, eventId);
        } catch (Exception e) {
            log.warn("Could not update diagnosis on CB event {}: {}", eventId, e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("event_id", eventId);
        result.put("level", level);
        result.put("diagnosis", diagnosis);
        result.put("confidence", confidence);
        result.put("resume_steps", resumeSteps);
        result.put("evidence", evidence);
        return result;
    }

    /**
     * Record the human-confirmed cause for a CB event.
     */
    public void confirmCause(UUID tenantId, UUID eventId, String cause) {
        jdbc.update("""
                UPDATE circuit_breaker_events
                SET confirmed_cause = ?
                WHERE id = ? AND tenant_id = ?
                """, cause, eventId, tenantId);
        log.info("CB event {} confirmed cause: {}", eventId, cause);
    }

    /**
     * Batch-resume CB events. Checks autonomy level before acting.
     * Returns count of events resumed.
     */
    public int resumeBatch(UUID tenantId, List<UUID> eventIds) {
        autonomyService.checkAndExecute(tenantId, "CB_RESUME", () -> {
            // autonomy check passed — proceed
        });

        int count = 0;
        for (UUID id : eventIds) {
            try {
                int updated = jdbc.update("""
                        UPDATE circuit_breaker_events
                        SET resolved_at = now(), resolved_by = 'auto_resume', outcome = 'RESUMED'
                        WHERE id = ? AND tenant_id = ? AND resolved_at IS NULL
                        """, id, tenantId);
                count += updated;
            } catch (Exception e) {
                log.warn("Failed to resume CB event {}: {}", id, e.getMessage());
            }
        }
        log.info("RecoveryDiagnosis: resumed {} CB events for tenant={}", count, tenantId);
        return count;
    }

    private long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }
}
