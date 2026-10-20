package com.marketingai.scheduler;

import com.marketingai.service.PersonaModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Roadmap 9-9 / arch §15.9: weekly persona A/B comparison → PREFILTER promotion. Monday 07:30 UTC. */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaPromotionScheduler {

    private final JdbcTemplate jdbc;
    private final PersonaModeService personaModeService;

    @Scheduled(cron = "0 30 7 * * MON", zone = "UTC")
    public void weekly() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants WHERE COALESCE(settings->>'persona_mode', 'AB_TEST') = 'AB_TEST'");
        for (Map<String, Object> t : tenants) {
            UUID tenantId = (UUID) t.get("id");
            try {
                Map<String, Object> r = personaModeService.weeklyCompare(tenantId);
                log.info("PersonaPromotion: tenant={} {}", tenantId, r);
            } catch (Exception e) {
                log.warn("PersonaPromotion: tenant {} skipped: {}", tenantId, e.getMessage());
            }
        }
    }
}
