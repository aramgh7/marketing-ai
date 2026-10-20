package com.marketingai.scheduler;

import com.marketingai.service.StageValueMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Weekly stage-value refresh (roadmap 9-8; arch §16.4): Monday 07:00 UTC.
 * E[funds | session reached stage] from the last 180 days: closed deals = sessions with revenue
 * (DEAL_WON / FUNDS_RECEIVED), reach = distinct sessions with the stage's mapped event type.
 * Needs ≥ 20 closed deals; writes funnel_stages.expected_value_usd (replaces the §19.2 seeds).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageValueRefreshScheduler {

    static final int WINDOW_DAYS = 180;

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 7 * * MON", zone = "UTC")
    public void refreshAll() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                int n = refreshTenant(tenantId);
                if (n > 0) log.info("StageValueRefresh tenant={} stages updated={}", tenantId, n);
            } catch (Exception e) {
                log.warn("StageValueRefresh tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Package-private for tests. Returns the number of stages updated. */
    int refreshTenant(UUID tenantId) {
        List<Double> closed = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT revenue FROM sessions
                WHERE tenant_id = ? AND converted = TRUE AND revenue IS NOT NULL AND revenue > 0
                  AND started_at > now() - (? * INTERVAL '1 day')
                """, tenantId, WINDOW_DAYS)) {
            closed.add(((Number) r.get("revenue")).doubleValue());
        }
        if (closed.size() < StageValueMath.MIN_CLOSED_DEALS) return 0;

        Map<String, Long> reached = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT fs.stage_key,
                       (SELECT COUNT(DISTINCT e.session_id) FROM events e
                         WHERE e.tenant_id = fs.tenant_id AND e.event_type = fs.maps_to_event_type
                           AND e.event_time > now() - (? * INTERVAL '1 day')) AS reached
                FROM funnel_stages fs
                WHERE fs.tenant_id = ? AND fs.maps_to_event_type IS NOT NULL
                """, WINDOW_DAYS, tenantId)) {
            reached.put((String) r.get("stage_key"), ((Number) r.get("reached")).longValue());
        }

        Map<String, StageValueMath.StageValue> values = StageValueMath.expectedValues(reached, closed);
        int updated = 0;
        for (Map.Entry<String, StageValueMath.StageValue> e : values.entrySet()) {
            updated += jdbc.update("""
                    UPDATE funnel_stages SET expected_value_usd = ?, value_n = ?, value_refreshed_at = now()
                    WHERE tenant_id = ? AND stage_key = ?
                    """, (float) e.getValue().expectedValueUsd(), (int) e.getValue().nReached(), tenantId, e.getKey());
        }
        return updated;
    }
}
