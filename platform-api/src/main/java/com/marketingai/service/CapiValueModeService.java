package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * capi_value_mode = PROXY | FIXED | AB (roadmap 9-9; arch §15.19).
 *   tenantMode(tenantId)                 tenants.settings.capi_value_mode (default PROXY)
 *   effectiveModeForSession(tenant, sid) AB → the ad set the session came from (sessions.creative_id →
 *                                        meta_ad_sets.creative_id, TEST contour first); no match → PROXY
 *   assignPendingAdSets(tenantId)        AB → alternate PROXY / FIXED on ad sets without value_mode
 *   weeklyCompare(tenantId)              cost per CALL_HELD per arm → capi_value_mode_runs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapiValueModeService {

    public static final int WINDOW_DAYS = 28;
    private final JdbcTemplate jdbc;

    public String tenantMode(UUID tenantId) {
        try {
            String s = jdbc.queryForObject("SELECT settings->>'capi_value_mode' FROM tenants WHERE id = ?",
                    String.class, tenantId);
            return CapiValueModeMath.resolveMode(s);
        } catch (Exception e) {
            return "PROXY";
        }
    }

    /** PROXY / FIXED for one event. Sessions that cannot be tied to an ad set fall back to PROXY. */
    public String effectiveModeForSession(UUID tenantId, String sessionId) {
        String mode = tenantMode(tenantId);
        if (!"AB".equals(mode)) return mode;
        if (sessionId == null) return "PROXY";
        try {
            List<String> modes = jdbc.queryForList("""
                    SELECT a.value_mode
                    FROM sessions s
                    JOIN meta_ad_sets a ON a.tenant_id = s.tenant_id AND a.creative_id = s.creative_id
                    JOIN meta_campaigns c ON c.id = a.campaign_id
                    WHERE s.id = ? AND s.tenant_id = ? AND a.value_mode IS NOT NULL
                    ORDER BY (c.contour = 'TEST') DESC, a.created_at DESC
                    LIMIT 1
                    """, String.class, sessionId, tenantId);
            return CapiValueModeMath.effectiveMode(mode, modes.isEmpty() ? null : modes.get(0));
        } catch (Exception e) {
            log.debug("capi_value_mode lookup failed for session {}: {}", sessionId, e.getMessage());
            return "PROXY";
        }
    }

    /** AB: give every ad set without a value_mode an arm, alternating from the current count. */
    public int assignPendingAdSets(UUID tenantId) {
        if (!"AB".equals(tenantMode(tenantId))) return 0;
        Long assigned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM meta_ad_sets WHERE tenant_id = ? AND value_mode IS NOT NULL", Long.class, tenantId);
        List<UUID> pending = jdbc.queryForList(
                "SELECT id FROM meta_ad_sets WHERE tenant_id = ? AND value_mode IS NULL ORDER BY created_at",
                UUID.class, tenantId);
        long n = assigned == null ? 0 : assigned;
        for (UUID id : pending) {
            jdbc.update("UPDATE meta_ad_sets SET value_mode = ? WHERE id = ?", CapiValueModeMath.alternate(n++), id);
        }
        return pending.size();
    }

    /** Weekly comparison on cost per CALL_HELD (appointments COMPLETED) per arm over the last 28 days. */
    public Map<String, Object> weeklyCompare(UUID tenantId) {
        Map<String, Object> row = jdbc.queryForMap("""
                WITH arms AS (
                    -- spend in the window: cumulative insight snapshots (MAX − MIN); fallback spend_total
                    SELECT a.value_mode, a.creative_id,
                           COALESCE(MAX(i.spend) - MIN(i.spend), a.spend_total, 0) AS spend
                    FROM meta_ad_sets a
                    LEFT JOIN meta_insights_history i ON i.ad_set_id = a.meta_ad_set_id AND i.tenant_id = a.tenant_id
                         AND i.recorded_at >= now() - (? * interval '1 day')
                    WHERE a.tenant_id = ? AND a.value_mode IS NOT NULL
                    GROUP BY a.id, a.value_mode, a.creative_id, a.spend_total
                ), held AS (
                    SELECT s.creative_id, COUNT(*) AS held
                    FROM appointments ap JOIN sessions s ON s.id = ap.session_id
                    WHERE ap.tenant_id = ? AND ap.status = 'COMPLETED'
                      AND ap.scheduled_at >= now() - (? * interval '1 day')
                    GROUP BY s.creative_id
                )
                SELECT COALESCE(SUM(arms.spend) FILTER (WHERE value_mode = 'PROXY'), 0)                      AS proxy_spend,
                       COALESCE(SUM(held.held)  FILTER (WHERE value_mode = 'PROXY'), 0)                      AS proxy_held,
                       COALESCE(SUM(arms.spend) FILTER (WHERE value_mode = 'FIXED'), 0)                      AS fixed_spend,
                       COALESCE(SUM(held.held)  FILTER (WHERE value_mode = 'FIXED'), 0)                      AS fixed_held
                FROM arms LEFT JOIN held ON held.creative_id = arms.creative_id
                """, WINDOW_DAYS, tenantId, tenantId, WINDOW_DAYS);
        double ps = d(row.get("proxy_spend")), fs = d(row.get("fixed_spend"));
        int ph = (int) d(row.get("proxy_held")), fh = (int) d(row.get("fixed_held"));
        CapiValueModeMath.Comparison c = CapiValueModeMath.compare(ps, ph, fs, fh);
        jdbc.update("""
                INSERT INTO capi_value_mode_runs
                    (tenant_id, window_days, proxy_spend, proxy_held, fixed_spend, fixed_held,
                     proxy_cost_per_held, fixed_cost_per_held, winner, lift_pct)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, WINDOW_DAYS, (float) ps, ph, (float) fs, fh,
                c.proxyCostPerHeld() == null ? null : c.proxyCostPerHeld().floatValue(),
                c.fixedCostPerHeld() == null ? null : c.fixedCostPerHeld().floatValue(),
                c.winner(), c.liftPct() == null ? null : c.liftPct().floatValue());
        return Map.of("proxy_spend", ps, "proxy_held", ph, "fixed_spend", fs, "fixed_held", fh,
                "winner", c.winner(), "lift_pct", c.liftPct() == null ? 0.0 : c.liftPct());
    }

    /** One line for the weekly report (WeeklyReportScheduler wiring point). */
    public String weeklyLine(UUID tenantId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT winner, lift_pct, proxy_cost_per_held, fixed_cost_per_held, proxy_held, fixed_held
                    FROM capi_value_mode_runs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1
                    """, tenantId);
            if (rows.isEmpty()) return "CAPI value A/B: no run yet";
            Map<String, Object> r = rows.get(0);
            return String.format(java.util.Locale.ROOT,
                    "CAPI value A/B: %s (proxy $%.0f/held n=%s, fixed $%.0f/held n=%s, lift %.1f%%)",
                    r.get("winner"), d(r.get("proxy_cost_per_held")), r.get("proxy_held"),
                    d(r.get("fixed_cost_per_held")), r.get("fixed_held"), d(r.get("lift_pct")));
        } catch (Exception e) {
            return "CAPI value A/B: unavailable";
        }
    }

    private static double d(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
