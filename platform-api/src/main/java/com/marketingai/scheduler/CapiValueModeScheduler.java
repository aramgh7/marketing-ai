package com.marketingai.scheduler;

import com.marketingai.service.CapiValueModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap 9-9 / arch §15.19: capi_value_mode = AB. Hourly arm assignment for new ad sets,
 * weekly comparison on cost per CALL_HELD (Monday 07:15 UTC) → capi_value_mode_runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapiValueModeScheduler {

    private final JdbcTemplate jdbc;
    private final CapiValueModeService service;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void assignArms() {
        for (UUID tenantId : abTenants()) {
            try {
                int n = service.assignPendingAdSets(tenantId);
                if (n > 0) log.info("CapiValueMode: tenant={} assigned {} ad sets", tenantId, n);
            } catch (Exception e) {
                log.warn("CapiValueMode assign: tenant {} skipped: {}", tenantId, e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 15 7 * * MON", zone = "UTC")
    public void weekly() {
        for (UUID tenantId : abTenants()) {
            try {
                Map<String, Object> r = service.weeklyCompare(tenantId);
                log.info("CapiValueMode weekly: tenant={} {}", tenantId, r);
            } catch (Exception e) {
                log.warn("CapiValueMode weekly: tenant {} skipped: {}", tenantId, e.getMessage());
            }
        }
    }

    private List<UUID> abTenants() {
        return jdbc.queryForList("SELECT id FROM tenants WHERE settings->>'capi_value_mode' = 'AB'", UUID.class);
    }
}
