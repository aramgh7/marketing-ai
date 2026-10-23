package com.marketingai.scheduler;

import com.marketingai.service.AutopilotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Hourly autopilot evaluation (Phase 6-6).
 * Only runs for tenants with enabled=true in autopilot_settings.
 * Safety: max_l1_triggers_per_day=3 cap enforced inside AutopilotService.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutopilotScheduler {

    private final JdbcTemplate    jdbc;
    private final AutopilotService autopilotService;

    @Scheduled(cron = "0 0 * * * *", zone = "UTC")
    public void runHourlyEvaluation() {
        List<UUID> enabledTenants = jdbc.queryForList("""
                SELECT tenant_id FROM autopilot_settings WHERE enabled = TRUE
                """, UUID.class);

        for (UUID tenantId : enabledTenants) {
            try {
                Map<String, Object> result = autopilotService.evaluateActions(tenantId);
                log.info("Autopilot [{}]: {}", tenantId, result);
            } catch (Exception e) {
                log.warn("Autopilot eval failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }
}
