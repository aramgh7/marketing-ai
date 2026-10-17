package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly show-rate corrector (roadmap 8-3; vision §19.10).
 * Every Monday 06:00 UTC: computes Beta-shrinkage multiplier from BOOKED/CALL_HELD
 * and writes it to tenants.settings.show_rate_multiplier.
 * Gate 2 += booking_rate: gates tighten when show rate is below prior.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShowRateCorrectorScheduler {

    private final JdbcTemplate  jdbc;
    private final RestTemplate  restTemplate;

    @Scheduled(cron = "0 0 6 * * MON")
    public void weeklyCorrection() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants WHERE is_active = TRUE");
        for (Map<String, Object> tenant : tenants) {
            try {
                correct((UUID) tenant.get("id"));
            } catch (Exception e) {
                log.warn("ShowRateCorrector failed for tenant {}: {}", tenant.get("id"), e.getMessage());
            }
        }
    }

    private void correct(UUID tenantId) {
        Map<String, Object> counts = jdbc.queryForMap("""
            SELECT
                COUNT(*) FILTER (WHERE stage_key = 'BOOKED')    AS booked_count,
                COUNT(*) FILTER (WHERE stage_key = 'CALL_HELD') AS held_count
            FROM reward_events
            WHERE tenant_id = ? AND rewarded_at >= NOW() - INTERVAL '7 days'
            """, tenantId);

        int booked = ((Number) counts.get("booked_count")).intValue();
        int held   = ((Number) counts.get("held_count")).intValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                "http://ml-service:8001/reward/show-rate-corrector",
                Map.of("booked_count", booked, "held_count", held),
                Map.class);

        if (result != null && result.get("multiplier") != null) {
            double multiplier = ((Number) result.get("multiplier")).doubleValue();
            jdbc.update("""
                UPDATE tenants
                SET settings = jsonb_set(
                    COALESCE(settings, '{}'),
                    '{show_rate_multiplier}',
                    to_jsonb(?)
                )
                WHERE id = ?
                """, multiplier, tenantId);
            log.info("ShowRateCorrector: tenant={} booked={} held={} multiplier={}",
                    tenantId, booked, held, multiplier);
        }
    }
}
