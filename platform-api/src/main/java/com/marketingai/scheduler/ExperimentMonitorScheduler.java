package com.marketingai.scheduler;

import com.marketingai.service.ExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExperimentMonitorScheduler {

    private final JdbcTemplate      jdbc;
    private final ExperimentService experimentService;

    @Scheduled(fixedDelay = 3_600_000)
    public void monitor() {
        List<Map<String, Object>> active = jdbc.queryForList(
            "SELECT id, tenant_id FROM experiments WHERE status = 'ACTIVE'");
        for (Map<String, Object> exp : active) {
            try {
                UUID tenantId     = (UUID) exp.get("tenant_id");
                UUID experimentId = (UUID) exp.get("id");
                experimentService.checkExperiment(tenantId, experimentId);
            } catch (Exception e) {
                log.warn("Experiment monitor error: {}", e.getMessage());
            }
        }
        if (!active.isEmpty()) {
            log.info("ExperimentMonitorScheduler: checked {} active experiments", active.size());
        }
    }
}
