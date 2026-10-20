package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * No-show report data (roadmap 8-15; vision §19.18.2): show rate by risk tier, team-slot fill
 * rate and the rolling-holdout AUC of the no-show propensity model. Rows are collected here,
 * the arithmetic lives in ml-service {@code noshow_report_service.py} (POST /propensity/report).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NoshowReportService {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;

    @SuppressWarnings("unchecked")
    public Map<String, Object> report(UUID tenantId, int days) {
        // Resolved appointments with the score that existed when they were booked
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ns.noshow_prob, ns.risk_tier, a.status AS outcome,
                       ns.scored_at::text AS scored_at, a.scheduled_at::text AS scheduled_at
                FROM appointments a
                JOIN LATERAL (
                    SELECT noshow_prob, risk_tier, scored_at
                    FROM noshow_scores s
                    WHERE s.appointment_id = a.id AND s.tenant_id = a.tenant_id
                    ORDER BY s.scored_at ASC LIMIT 1
                ) ns ON TRUE
                WHERE a.tenant_id = ?
                  AND a.status IN ('COMPLETED', 'NO_SHOW')
                  AND a.scheduled_at >= now() - (? * INTERVAL '1 day')
                """, tenantId, days);

        // Team slots: appointments grouped by the hour they were scheduled in; capacity from the policy
        List<Map<String, Object>> slots = jdbc.queryForList("""
                SELECT date_trunc('hour', a.scheduled_at)::text AS slot_at,
                       COALESCE((SELECT 1 + p.max_overbook FROM slot_capacity_policies p
                                 WHERE p.tenant_id = a.tenant_id AND p.enabled = TRUE
                                 ORDER BY p.calendar_id NULLS FIRST LIMIT 1), 1)  AS capacity,
                       COUNT(*)                                                   AS booked,
                       COUNT(*) FILTER (WHERE a.status = 'COMPLETED')             AS showed
                FROM appointments a
                WHERE a.tenant_id = ?
                  AND a.scheduled_at BETWEEN now() - (? * INTERVAL '1 day') AND now()
                GROUP BY date_trunc('hour', a.scheduled_at), a.tenant_id
                ORDER BY 1 DESC
                LIMIT 500
                """, tenantId, days);

        Map<String, Object> result = mlClient.post("/propensity/report",
                Map.of("tenant_id", tenantId.toString(), "rows", rows, "slots", slots, "min_lead_days", 1.0), Map.class);
        return result != null ? result : Map.of("error", "ml-service unavailable", "n_rows", rows.size());
    }

    /** Short lines for the weekly report. */
    @SuppressWarnings("unchecked")
    public String weeklyLines(UUID tenantId) {
        try {
            Map<String, Object> r = report(tenantId, 7);
            StringBuilder sb = new StringBuilder();
            List<Map<String, Object>> tiers = (List<Map<String, Object>>) r.getOrDefault("by_tier", List.of());
            for (Map<String, Object> t : tiers) {
                Object rate = t.get("show_rate");
                sb.append(String.format(java.util.Locale.ROOT, "Show rate %-4s: %s (n=%s)\n", t.get("risk_tier"),
                        rate == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.0f%% [%.0f–%.0f%%]",
                                ((Number) rate).doubleValue() * 100, ((Number) t.get("ci_low")).doubleValue() * 100,
                                ((Number) t.get("ci_high")).doubleValue() * 100), t.get("n")));
            }
            Map<String, Object> fill = (Map<String, Object>) r.getOrDefault("slot_fill", Map.of());
            Object fr = fill.get("fill_rate");
            sb.append("Team-slot fill: ").append(fr == null ? "n/a" :
                    String.format(java.util.Locale.ROOT, "%.0f%% (%s showed / %s capacity, %s overflow slots)",
                            ((Number) fr).doubleValue() * 100, fill.get("showed"), fill.get("capacity"), fill.get("overflow_slots"))).append("\n");
            Map<String, Object> auc = (Map<String, Object>) r.getOrDefault("holdout_auc", Map.of());
            Object a = auc.get("auc");
            sb.append("No-show model AUC (holdout): ").append(a == null ? "n/a" :
                    String.format(java.util.Locale.ROOT, "%.2f (gate %.2f, %s, n=%s)", ((Number) a).doubleValue(),
                            ((Number) auc.getOrDefault("gate", 0.7)).doubleValue(),
                            Boolean.TRUE.equals(auc.get("passes_gate")) ? "PASS" : "below gate", auc.get("n")));
            return sb.toString();
        } catch (Exception e) {
            return "No-show report: unavailable (" + e.getMessage() + ")";
        }
    }
}
