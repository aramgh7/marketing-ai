package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.CvrCollapseMath;
import com.marketingai.service.MetaApiClient;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.SiteHealthResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * CVR-collapse detector (roadmap 9-8; arch §9 / §15.3): every 15 min, per ACTIVE landing variant,
 * the last 24 h of hourly sessions/conversions go to ml-service {@code POST /signals/cvr-collapse}.
 * Verdict COLLAPSE (traffic stable, conversions → 0 within the last hour) opens a
 * {@code site_health_incidents} row with cause CVR_COLLAPSE, pauses the tenant's ACTIVE ad sets through
 * the P0 lane (the same path as the HTTP outage autopause) and alerts. Resume follows the confirmed
 * site-health protocol (3 passes + manual confirmation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CvrCollapseScheduler {

    static final long MIN_SESSIONS_24H = 200;

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;
    private final AlertService alertService;
    private final MetaApiClient metaApiClient;
    private final SiteHealthResumeService resumeService;

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void scan() {
        List<UUID> tenants = jdbc.queryForList("SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                scanTenant(tenantId, Instant.now());
            } catch (Exception e) {
                log.warn("CvrCollapse tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    int scanTenant(UUID tenantId, Instant now) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.variant_id, date_trunc('hour', s.started_at) AS hour,
                       COUNT(*) AS sessions, COUNT(*) FILTER (WHERE s.converted) AS conversions
                FROM sessions s
                JOIN landing_variants lv ON lv.id = s.variant_id AND lv.status = 'ACTIVE'
                WHERE s.tenant_id = ? AND s.variant_id IS NOT NULL
                  AND s.started_at > now() - INTERVAL '24 hours'
                GROUP BY s.variant_id, date_trunc('hour', s.started_at)
                """, tenantId);
        if (rows.isEmpty()) return 0;

        Map<UUID, List<CvrCollapseMath.HourRow>> byVariant = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Instant h = ((Timestamp) r.get("hour")).toInstant();
            byVariant.computeIfAbsent((UUID) r.get("variant_id"), k -> new ArrayList<>())
                    .add(new CvrCollapseMath.HourRow(h, ((Number) r.get("sessions")).longValue(),
                            ((Number) r.get("conversions")).longValue()));
        }

        List<Map<String, Object>> entities = new ArrayList<>();
        for (Map.Entry<UUID, List<CvrCollapseMath.HourRow>> e : byVariant.entrySet()) {
            List<Map<String, Object>> series = CvrCollapseMath.denseSeries(e.getValue(), now);
            if (CvrCollapseMath.worthChecking(series, MIN_SESSIONS_24H)) {
                entities.add(Map.of("id", e.getKey().toString(), "series", series));
            }
        }
        if (entities.isEmpty()) return 0;

        Map<String, Object> resp = mlClient.post("/signals/cvr-collapse",
                Map.of("tenant_id", tenantId.toString(), "entities", entities, "min_sessions", 100), Map.class);
        if (resp == null) return 0;
        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.getOrDefault("results", List.of());
        int collapsed = 0;
        for (Map<String, Object> res : results) {
            if (!"COLLAPSE".equals(res.get("verdict"))) continue;
            UUID variantId = UUID.fromString(String.valueOf(res.get("id")));
            if (openIncident(tenantId, variantId, res)) collapsed++;
        }
        return collapsed;
    }

    /** Opens the incident once per variant (no duplicate while one is unresolved); returns true when opened. */
    boolean openIncident(UUID tenantId, UUID variantId, Map<String, Object> res) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM site_health_incidents
                WHERE tenant_id = ? AND variant_id = ? AND cause = 'CVR_COLLAPSE' AND resolved_at IS NULL
                """, Integer.class, tenantId, variantId);
        if (open != null && open > 0) return false;

        String detail = String.format(Locale.ROOT,
                "{\"cvr_last\": %s, \"cvr_mean_24h\": %s, \"sessions_last_hour\": %s, \"reason\": \"%s\"}",
                num(res.get("cvr_last")), num(res.get("cvr_mean_24h")), num(res.get("sessions_last")),
                String.valueOf(res.getOrDefault("reason", "")).replace("\"", "'"));
        UUID incidentId = jdbc.queryForObject("""
                INSERT INTO site_health_incidents (tenant_id, variant_id, cause, consecutive_fails, alerted, detail)
                VALUES (?, ?, 'CVR_COLLAPSE', 1, TRUE, ?::jsonb)
                RETURNING id
                """, UUID.class, tenantId, variantId, detail);

        alertService.sendAlert("🔴 CVR collapse on variant " + variantId + ": conversions dropped to "
                + num(res.get("cvr_last")) + " (24 h mean " + num(res.get("cvr_mean_24h"))
                + ") on stable traffic. Incident opened; ad sets paused (P0). Confirm resume after the fix.");
        log.warn("CVR collapse incident opened: tenant={} variant={} incident={}", tenantId, variantId, incidentId);

        metaApiClient.enqueue("P0", null, tenantId, "AUTOPAUSE_CVR_COLLAPSE", () -> {
            List<UUID> ids = jdbc.queryForList(
                    "SELECT id FROM meta_ad_sets WHERE tenant_id = ? AND status = 'ACTIVE'", UUID.class, tenantId);
            int paused = jdbc.update(
                    "UPDATE meta_ad_sets SET status='PAUSED', synced_at=now() WHERE tenant_id=? AND status='ACTIVE'",
                    tenantId);
            if (paused > 0) {
                resumeService.rememberPausedAdSets(incidentId, ids);
                log.warn("CVR collapse P0 autopause: tenant={} paused {} ad sets", tenantId, paused);
            }
        });
        return true;
    }

    private static String num(Object o) {
        return o instanceof Number n ? String.valueOf(n.doubleValue()) : "null";
    }
}
