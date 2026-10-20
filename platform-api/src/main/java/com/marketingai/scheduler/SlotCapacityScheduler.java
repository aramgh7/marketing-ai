package com.marketingai.scheduler;

import com.marketingai.service.SlotCapacityMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slot-capacity refresh (roadmap 9-8; arch §16.15): every 15 min the enabled
 * {@code slot_capacity_policies} get max_overbook recomputed from the last 30 days' no-show mix:
 * overbook = floor(avg booked per hour slot × p_noshow), capped at 2. Tenants without an enabled policy
 * are left alone — enabling overbooking is a coordinator decision, not a job's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotCapacityScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 4 * 60 * 1000)
    public void refreshAll() {
        List<UUID> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM slot_capacity_policies WHERE enabled = TRUE", UUID.class);
        for (UUID tenantId : tenants) {
            try {
                refreshTenant(tenantId);
            } catch (Exception e) {
                log.warn("SlotCapacity tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Package-private for tests. Returns the new overbook or -1 when there is not enough data. */
    int refreshTenant(UUID tenantId) {
        Map<String, Object> mix = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       COUNT(*) FILTER (WHERE status = 'NO_SHOW')   AS no_show,
                       COALESCE((SELECT AVG(n) FROM (
                            SELECT COUNT(*) AS n FROM appointments a2
                            WHERE a2.tenant_id = a.tenant_id AND a2.scheduled_at > now() - INTERVAL '30 days'
                            GROUP BY date_trunc('hour', a2.scheduled_at)) slots), 0) AS avg_booked
                FROM appointments a
                WHERE a.tenant_id = ? AND a.scheduled_at > now() - INTERVAL '30 days'
                GROUP BY a.tenant_id
                """, tenantId);
        SlotCapacityMath.NoShowMix m = new SlotCapacityMath.NoShowMix(
                ((Number) mix.get("completed")).longValue(), ((Number) mix.get("no_show")).longValue());
        if (!SlotCapacityMath.enoughData(m)) return -1;
        double avgBooked = ((Number) mix.get("avg_booked")).doubleValue();
        int overbook = SlotCapacityMath.overbook(m.pNoShow(), avgBooked);
        jdbc.update("""
                UPDATE slot_capacity_policies SET max_overbook = ?, p_noshow = ?, computed_at = now()
                WHERE tenant_id = ? AND enabled = TRUE
                """, overbook, (float) m.pNoShow(), tenantId);
        log.debug("SlotCapacity tenant={} p_noshow={} avg_booked={} overbook={}", tenantId, m.pNoShow(), avgBooked, overbook);
        return overbook;
    }
}
