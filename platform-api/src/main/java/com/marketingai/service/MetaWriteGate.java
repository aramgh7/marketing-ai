package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.security.TenantContext;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single choke point for Meta Marketing API writes (roadmap 9-19; .ai/meta-api-policy.md §6).
 * Every write call site invokes {@link #assertAllowed} first; the decision is written to
 * {@code meta_write_decisions} whatever the verdict. DENY → 403, RECOMMEND → 409 (+ a PENDING row in
 * {@code autopilot_actions} so a human can execute it from the UI). {@code MetaWriteGuardTest} scans the
 * sources so no new write path can skip the gate unnoticed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaWriteGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public record Request(MetaWriteAction action, String targetId, Double budgetDeltaPct, boolean probe) {
        public static Request of(MetaWriteAction action) { return new Request(action, null, null, false); }
        public static Request of(MetaWriteAction action, String targetId) { return new Request(action, targetId, null, false); }
    }

    /** Evaluate, record and enforce. Returns the decision when allowed; throws otherwise. */
    public MetaWritePolicy.Decision assertAllowed(UUID tenantId, Request req) {
        MetaWritePolicy.Decision d = decide(tenantId, req);
        switch (d.verdict()) {
            case ALLOW -> { return d; }
            case RECOMMEND -> {
                recordRecommendation(tenantId, req, d);
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Meta write recorded as a recommendation: " + d.reason());
            }
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meta write refused: " + d.reason());
        }
    }

    /** Evaluate and record without throwing (for schedulers that want to skip quietly). */
    public MetaWritePolicy.Decision decide(UUID tenantId, Request req) {
        Map<String, Object> tenant = tenantRow(tenantId);
        MetaWritePolicy.Stage stage = MetaWritePolicy.parseStage((String) tenant.get("meta_write_stage"));
        String autonomy = tenant.get("autonomy_level") == null ? "ADVISOR" : (String) tenant.get("autonomy_level");
        MetaWritePolicy.Thresholds t = thresholds(tenant.get("meta_write_policy"));
        MetaWritePolicy.Initiator initiator = TenantContext.isSystem() ? MetaWritePolicy.Initiator.JOB : MetaWritePolicy.Initiator.USER;

        UUID overrideId = activeOverride(tenantId, req.action(), req.targetId());
        MetaWritePolicy.Context ctx = new MetaWritePolicy.Context(
                stage, autonomy, initiator, req.action(), req.targetId(), req.budgetDeltaPct(),
                hoursSinceLastBudgetChange(tenantId, req.targetId()),
                writesLastHour(tenantId),
                learningPhase(tenantId, req.targetId()),
                req.probe(), overrideId != null);
        MetaWritePolicy.Decision d = MetaWritePolicy.evaluate(ctx, t);

        UUID decisionId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO meta_write_decisions
                        (id, tenant_id, action, action_class, target_id, initiator, stage, autonomy_level,
                         verdict, reason, budget_delta_pct, override_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, decisionId, tenantId, req.action().name(), req.action().actionClass(), req.targetId(),
                    initiator.name(), stage.name(), autonomy, d.verdict().name(), d.reason(),
                    req.budgetDeltaPct() == null ? null : req.budgetDeltaPct().floatValue(), overrideId);
            if (overrideId != null && d.allowed()) {
                jdbc.update("UPDATE meta_write_overrides SET used_at = now(), used_decision_id = ? WHERE id = ?", decisionId, overrideId);
            }
        } catch (Exception e) {
            log.warn("meta_write_decisions insert failed: {}", e.getMessage());
        }
        if (d.verdict() != MetaWritePolicy.Verdict.ALLOW) {
            log.info("MetaWriteGate {}: tenant={} action={} target={} — {}", d.verdict(), tenantId, req.action(), req.targetId(), d.reason());
        }
        return d;
    }

    // ─── facts ───────────────────────────────────────────────────────────────

    private Map<String, Object> tenantRow(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT meta_write_stage, meta_write_policy, autonomy_level FROM tenants WHERE id = ?", tenantId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    static MetaWritePolicy.Thresholds thresholds(Object policyJson) {
        MetaWritePolicy.Thresholds d = MetaWritePolicy.Thresholds.defaults();
        try {
            String json = Jsonb.asString(policyJson);
            if (json == null || json.isBlank()) return d;
            JsonNode n = MAPPER.readTree(json);
            return new MetaWritePolicy.Thresholds(
                    n.path("max_budget_change_pct").asDouble(d.maxBudgetChangePct()),
                    n.path("min_hours_between_budget_changes").asDouble(d.minHoursBetweenBudgetChanges()),
                    n.path("max_writes_per_hour").asInt(d.maxWritesPerHour()),
                    n.path("learning_phase_lock").asBoolean(d.learningPhaseLock()),
                    n.path("launch_requires_probe").asBoolean(d.launchRequiresProbe()));
        } catch (Exception e) {
            return d;
        }
    }

    private UUID activeOverride(UUID tenantId, MetaWriteAction action, String targetId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM meta_write_overrides
                WHERE tenant_id = ? AND used_at IS NULL AND expires_at > now()
                  AND (action = ? OR action = '*')
                  AND (target_id IS NULL OR target_id = ?)
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class, tenantId, action.name(), targetId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private Double hoursSinceLastBudgetChange(UUID tenantId, String targetId) {
        if (targetId == null) return null;
        List<Timestamp> ts = jdbc.queryForList("""
                SELECT MAX(created_at) FROM meta_write_decisions
                WHERE tenant_id = ? AND action = 'BUDGET_CHANGE' AND verdict = 'ALLOW' AND target_id = ?
                """, Timestamp.class, tenantId, targetId);
        if (ts.isEmpty() || ts.get(0) == null) return null;
        return Duration.between(ts.get(0).toInstant(), Instant.now()).toMinutes() / 60.0;
    }

    private int writesLastHour(UUID tenantId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM meta_write_decisions
                WHERE tenant_id = ? AND verdict = 'ALLOW' AND action_class <> 'PROTECTIVE'
                  AND created_at > now() - INTERVAL '1 hour'
                """, Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    private String learningPhase(UUID tenantId, String targetId) {
        if (targetId == null) return null;
        List<String> rows = jdbc.queryForList("""
                SELECT learning_phase FROM meta_ad_sets
                WHERE tenant_id = ? AND (meta_ad_set_id = ? OR id::text = ?)
                LIMIT 1
                """, String.class, tenantId, targetId, targetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void recordRecommendation(UUID tenantId, Request req, MetaWritePolicy.Decision d) {
        try {
            jdbc.update("""
                    INSERT INTO autopilot_actions (tenant_id, action_type, target_id, payload, status)
                    VALUES (?, ?, ?, ?::jsonb, 'PENDING')
                    """, tenantId, "META_" + req.action().name(), req.targetId(),
                    "{\"reason\":" + quote(d.reason()) + ",\"budget_delta_pct\":" + req.budgetDeltaPct() + "}");
        } catch (Exception e) {
            log.debug("autopilot_actions recommendation skipped: {}", e.getMessage());
        }
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }
}
