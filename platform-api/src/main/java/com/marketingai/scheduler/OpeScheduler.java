package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.AutonomyGate;
import com.marketingai.service.AutonomyService;
import com.marketingai.service.OpeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly off-policy evaluation (roadmap 9-3; arch §9 "Off-policy evaluation — weekly").
 * Monday 05:00 UTC, before the 08:00 weekly report. A FAIL while the tenant runs autonomously
 * (SEMI_AUTOPILOT / AUTOPILOT) drops the level to ADVISOR and alerts — the gate works both ways.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpeScheduler {

    private final JdbcTemplate jdbc;
    private final OpeService opeService;
    private final AutonomyService autonomyService;
    private final AlertService alertService;

    @Scheduled(cron = "0 0 5 * * MON", zone = "UTC")
    public void weeklyOpe() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id, name, COALESCE(autonomy_level, 'ADVISOR') AS autonomy_level FROM tenants");
        for (Map<String, Object> t : tenants) {
            UUID tenantId = (UUID) t.get("id");
            String name = (String) t.get("name");
            try {
                Map<String, Object> result = opeService.evaluate(tenantId);
                String verdict = String.valueOf(result.get("verdict"));
                String level = (String) t.get("autonomy_level");
                log.info("OpeScheduler: tenant={} verdict={} level={}", name, verdict, level);
                if ("FAIL".equals(verdict)) {
                    String fallback = AutonomyGate.levelAfterFailedOpe(level);
                    if (!fallback.equals(level)) {
                        autonomyService.setAutonomyLevel(tenantId, fallback);
                        alertService.sendAlert("⚠️ OPE FAIL for tenant " + name + ": autonomy " + level +
                                " → " + fallback + " (the current policy is not better than the logged one).");
                    }
                }
            } catch (Exception e) {
                log.warn("OpeScheduler: tenant {} skipped: {}", name, e.getMessage());
            }
        }
    }
}
