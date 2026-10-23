package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Stage transitions for the Meta write policy (roadmap 9-19; .ai/meta-api-policy.md §6).
 * Conditions are about data, never about calendar dates:
 *
 *   READ_ONLY → GUARDED_WRITES
 *     - Meta token status OK
 *     - CAPI live: ≥ 50 SENT events and the first one ≥ 14 days ago
 *     - CAPI EMQ pre-check passed
 *     - Meta Automated Rules + Account Spending Limit confirmed by the client (settings.meta_automated_rules_confirmed)
 *   GUARDED_WRITES → FULL
 *     - ≥ 14 days in GUARDED_WRITES
 *     - latest OPE verdict PASS ≤ 7 days
 *     - autonomy level ≥ SEMI_AUTOPILOT
 *     - no DENY decisions initiated by jobs in the last 7 days (the guarded stage ran clean; user clicks don't count)
 *
 * Downgrades are always allowed; a forward move with unmet conditions needs an override reason (admin).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaWriteStageService {

    public static final int CAPI_MIN_EVENTS = 50;
    public static final int CAPI_MIN_DAYS = 14;
    public static final int GUARDED_MIN_DAYS = 14;

    /** FULL condition "clean week": DENY decisions of the last 7 days whose initiator was a job (audit M-24). */
    static final String CLEAN_WEEK_SQL = """
            SELECT COUNT(*) FROM meta_write_decisions
            WHERE tenant_id = ? AND verdict = 'DENY' AND initiator = 'JOB' AND created_at > now() - INTERVAL '7 days'
            """;

    private final JdbcTemplate jdbc;
    private final CAPIEmqCheckService emqCheckService;
    private final OpeService opeService;
    private final AutonomyService autonomyService;

    public record Condition(String key, String label, boolean passed, String detail) {}

    public Map<String, Object> status(UUID tenantId) {
        Map<String, Object> t = jdbc.queryForMap(
                "SELECT meta_write_stage, meta_write_stage_changed_at, meta_write_policy::text AS policy FROM tenants WHERE id = ?", tenantId);
        String stage = (String) t.get("meta_write_stage");
        String next = nextStage(stage);
        List<Condition> conds = next == null ? List.of() : conditionsFor(tenantId, next, (Timestamp) t.get("meta_write_stage_changed_at"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", stage);
        out.put("stages", MetaWritePolicy.STAGES);
        out.put("stage_changed_at", String.valueOf(t.get("meta_write_stage_changed_at")));
        out.put("policy", t.get("policy"));
        out.put("thresholds", thresholdsMap(MetaWriteGate.thresholds(t.get("policy"))));
        out.put("next_stage", next);
        out.put("conditions", conds.stream().map(c -> Map.of("key", c.key(), "label", c.label(), "passed", c.passed(), "detail", c.detail())).toList());
        out.put("can_advance", next != null && conds.stream().allMatch(Condition::passed));
        return out;
    }

    public Map<String, Object> setStage(UUID tenantId, String target, String overrideReason, UUID userId) {
        if (!MetaWritePolicy.STAGES.contains(target == null ? "" : target.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stage must be one of " + MetaWritePolicy.STAGES);
        }
        target = target.toUpperCase();
        Map<String, Object> t = jdbc.queryForMap(
                "SELECT meta_write_stage, meta_write_stage_changed_at FROM tenants WHERE id = ?", tenantId);
        String current = (String) t.get("meta_write_stage");
        boolean forward = MetaWritePolicy.STAGES.indexOf(target) > MetaWritePolicy.STAGES.indexOf(current);
        List<Condition> unmet = List.of();
        if (forward) {
            // one step at a time: conditions of the immediate next stage must hold (or an override reason is given)
            String next = nextStage(current);
            if (!target.equals(next)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "advance one stage at a time: next is " + next);
            }
            unmet = conditionsFor(tenantId, target, (Timestamp) t.get("meta_write_stage_changed_at")).stream()
                    .filter(c -> !c.passed()).toList();
            if (!unmet.isEmpty() && (overrideReason == null || overrideReason.isBlank())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "unmet conditions: "
                        + unmet.stream().map(Condition::label).toList() + " — pass override_reason to force");
            }
        }
        jdbc.update("UPDATE tenants SET meta_write_stage = ?, meta_write_stage_changed_at = now() WHERE id = ?", target, tenantId);
        jdbc.update("""
                INSERT INTO meta_write_decisions (tenant_id, action, action_class, target_id, initiator, stage, verdict, reason)
                VALUES (?, 'STAGE_CHANGE', 'STAGE', ?, 'USER', ?, 'ALLOW', ?)
                """, tenantId, current + "→" + target, target,
                forward && !unmet.isEmpty() ? "forced by " + userId + ": " + overrideReason : (forward ? "conditions met" : "downgrade"));
        log.info("MetaWriteStage: tenant={} {} → {} ({})", tenantId, current, target, forward ? "forward" : "downgrade");
        return Map.of("stage", target, "previous", current, "forced", forward && !unmet.isEmpty());
    }

    public void updatePolicy(UUID tenantId, Map<String, Object> thresholds) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String k : List.of("max_budget_change_pct", "min_hours_between_budget_changes", "max_writes_per_hour",
                "learning_phase_lock", "launch_requires_probe")) {
            if (thresholds.containsKey(k)) clean.put(k, thresholds.get(k));
        }
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(clean);
            jdbc.update("UPDATE tenants SET meta_write_policy = meta_write_policy || ?::jsonb WHERE id = ?", json, tenantId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid policy: " + e.getMessage());
        }
    }

    static String nextStage(String stage) {
        int i = MetaWritePolicy.STAGES.indexOf(stage);
        return i >= 0 && i + 1 < MetaWritePolicy.STAGES.size() ? MetaWritePolicy.STAGES.get(i + 1) : null;
    }

    List<Condition> conditionsFor(UUID tenantId, String target, Timestamp stageChangedAt) {
        List<Condition> out = new ArrayList<>();
        if ("GUARDED_WRITES".equals(target)) {
            String tokenStatus = jdbc.queryForObject("SELECT COALESCE(meta_token_status, 'MISSING') FROM tenants WHERE id = ?", String.class, tenantId);
            out.add(new Condition("token", "Meta token status OK", "OK".equals(tokenStatus), "status = " + tokenStatus));

            Map<String, Object> capi = jdbc.queryForMap("""
                    SELECT COUNT(*) AS n, MIN(sent_at) AS first_sent FROM capi_events
                    WHERE tenant_id = ? AND status = 'SENT'
                    """, tenantId);
            long n = ((Number) capi.get("n")).longValue();
            Timestamp first = (Timestamp) capi.get("first_sent");
            long days = first == null ? 0 : Duration.between(first.toInstant(), Instant.now()).toDays();
            out.add(new Condition("capi_live", "CAPI live: ≥ " + CAPI_MIN_EVENTS + " sent events over ≥ " + CAPI_MIN_DAYS + " days",
                    n >= CAPI_MIN_EVENTS && days >= CAPI_MIN_DAYS, n + " sent, first " + days + " days ago"));

            boolean emq = false; String emqDetail = "not run";
            try {
                Map<String, Object> r = emqCheckService.runEmqPreCheck(tenantId);
                emq = Boolean.TRUE.equals(r.get("passed"));
                emqDetail = String.valueOf(r);
                if (emqDetail.length() > 200) emqDetail = emqDetail.substring(0, 200) + "…";
            } catch (Exception e) { emqDetail = "check failed: " + e.getMessage(); }
            out.add(new Condition("emq", "CAPI EMQ pre-check passed", emq, emqDetail));

            Boolean rules = jdbc.queryForObject(
                    "SELECT COALESCE((settings->>'meta_automated_rules_confirmed')::boolean, false) FROM tenants WHERE id = ?", Boolean.class, tenantId);
            out.add(new Condition("automated_rules", "Meta Automated Rules + Account Spending Limit confirmed by the client",
                    Boolean.TRUE.equals(rules), "tenants.settings.meta_automated_rules_confirmed"));
        } else if ("FULL".equals(target)) {
            long days = stageChangedAt == null ? 0 : Duration.between(stageChangedAt.toInstant(), Instant.now()).toDays();
            out.add(new Condition("guarded_days", "≥ " + GUARDED_MIN_DAYS + " days in GUARDED_WRITES", days >= GUARDED_MIN_DAYS, days + " days"));

            Optional<Map<String, Object>> ope = opeService.latest(tenantId);
            String verdict = ope.map(r -> String.valueOf(r.get("verdict"))).orElse("none");
            Long age = ope.map(r -> (Long) r.get("age_days")).orElse(null);
            out.add(new Condition("ope", "Latest OPE verdict PASS ≤ 7 days", "PASS".equals(verdict) && age != null && age <= AutonomyGate.MAX_OPE_AGE_DAYS,
                    verdict + (age == null ? "" : ", " + age + " days old")));

            String level = autonomyService.getAutonomyLevel(tenantId);
            out.add(new Condition("autonomy", "Autonomy ≥ SEMI_AUTOPILOT", AutonomyGate.rank(level) >= AutonomyGate.rank("SEMI_AUTOPILOT"), level));

            // Audit M-24: only writes the platform's jobs attempted count — a user clicking into a forbidden action
            // in the cabinet is a UI refusal, not evidence that the guarded stage ran dirty.
            Integer denies = jdbc.queryForObject(CLEAN_WEEK_SQL, Integer.class, tenantId);
            out.add(new Condition("clean_week", "No job writes refused in the last 7 days", denies != null && denies == 0,
                    denies + " DENY decisions initiated by jobs"));
        }
        return out;
    }

    private static Map<String, Object> thresholdsMap(MetaWritePolicy.Thresholds t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_budget_change_pct", t.maxBudgetChangePct());
        m.put("min_hours_between_budget_changes", t.minHoursBetweenBudgetChanges());
        m.put("max_writes_per_hour", t.maxWritesPerHour());
        m.put("learning_phase_lock", t.learningPhaseLock());
        m.put("launch_requires_probe", t.launchRequiresProbe());
        return m;
    }
}
