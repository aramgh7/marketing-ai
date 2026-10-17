package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Autopilot evaluation engine (Phase 6-6).
 *
 * Safety rules (§9.1):
 * - Autopilot is OFF by default; must be explicitly enabled per tenant.
 * - max_l1_triggers_per_day=3 hard cap prevents runaway automation.
 * - min_p_best_to_scale=0.95 (Thompson probability) before scaling a winner.
 * - Auto-deploy of competitive intelligence WITHOUT human approval: FORBIDDEN.
 * - Spring Boot is the ONLY server-side CAPI sender.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutopilotService {

    private final JdbcTemplate jdbc;

    public Map<String, Object> evaluateActions(UUID tenantId) {
        Map<String, Object> settings = loadSettings(tenantId);
        if (Boolean.FALSE.equals(settings.get("enabled"))) {
            return Map.of("evaluated", 0, "reason", "Autopilot disabled for this tenant");
        }

        int maxTriggers = ((Number) settings.getOrDefault("max_l1_triggers_per_day", 3)).intValue();
        int todayCount  = countTodayActions(tenantId);
        if (todayCount >= maxTriggers) {
            return Map.of("evaluated", 0, "reason", "Daily trigger cap reached (" + maxTriggers + ")");
        }

        List<Map<String, Object>> candidates = buildCandidates(tenantId, settings);
        int executed = 0;
        for (Map<String, Object> candidate : candidates) {
            if (todayCount + executed >= maxTriggers) break;
            if (isSafeToExecute(candidate, settings)) {
                executeAction(tenantId, candidate);
                executed++;
            }
        }

        return Map.of("evaluated", candidates.size(), "executed", executed);
    }

    public boolean isSafeToExecute(Map<String, Object> candidate, Map<String, Object> settings) {
        String actionType = (String) candidate.get("action_type");
        double minPBest   = ((Number) settings.getOrDefault("min_p_best_to_scale", 0.95)).doubleValue();

        return switch (actionType) {
            case "PAUSE_BURNOUT" ->
                    Boolean.TRUE.equals(settings.get("auto_pause_burnout"));
            case "SCALE_WINNER" -> {
                double pBest = candidate.containsKey("p_best")
                        ? ((Number) candidate.get("p_best")).doubleValue() : 0.0;
                yield Boolean.TRUE.equals(settings.get("auto_scale_winner")) && pBest >= minPBest;
            }
            case "RETIRE_GRAVEYARD" ->
                    Boolean.TRUE.equals(settings.get("auto_retire_graveyard"));
            default -> false;
        };
    }

    public void executeActions(UUID tenantId, List<Map<String, Object>> actions) {
        for (Map<String, Object> action : actions) {
            executeAction(tenantId, action);
        }
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    private Map<String, Object> loadSettings(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT enabled, auto_pause_burnout, auto_scale_winner, auto_retire_graveyard,
                       max_l1_triggers_per_day, min_p_best_to_scale, budget_scale_factor
                FROM autopilot_settings WHERE tenant_id = ?
                """, tenantId);
        return rows.isEmpty() ? Map.of("enabled", false) : rows.get(0);
    }

    private int countTodayActions(UUID tenantId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM autopilot_actions
                WHERE tenant_id = ? AND status = 'EXECUTED'
                  AND executed_at > now() - interval '1 day'
                """, Integer.class, tenantId);
        return count != null ? count : 0;
    }

    private List<Map<String, Object>> buildCandidates(UUID tenantId, Map<String, Object> settings) {
        List<Map<String, Object>> candidates = new ArrayList<>();

        if (Boolean.TRUE.equals(settings.get("auto_pause_burnout"))) {
            List<Map<String, Object>> burnout = jdbc.queryForList("""
                    SELECT id AS event_id, ad_set_id AS target_id
                    FROM creative_burnout_events
                    WHERE tenant_id = ? AND stage = 'critical' AND resolved = FALSE
                    LIMIT 3
                    """, tenantId);
            for (Map<String, Object> b : burnout) {
                candidates.add(Map.of(
                        "action_type", "PAUSE_BURNOUT",
                        "target_id",   b.get("target_id"),
                        "payload",     Map.of("event_id", b.get("event_id"))
                ));
            }
        }

        return candidates;
    }

    private void executeAction(UUID tenantId, Map<String, Object> candidate) {
        String actionType = (String) candidate.get("action_type");
        String targetId   = (String) candidate.get("target_id");
        Object payload    = candidate.getOrDefault("payload", Map.of());

        jdbc.update("""
                INSERT INTO autopilot_actions
                    (tenant_id, action_type, target_id, payload, status, executed_at)
                VALUES (?, ?, ?, ?::jsonb, 'EXECUTED', now())
                """, tenantId, actionType, targetId, payload.toString());

        log.info("Autopilot executed {} on target {} for tenant {}", actionType, targetId, tenantId);
    }
}
