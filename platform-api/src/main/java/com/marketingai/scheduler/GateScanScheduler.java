package com.marketingai.scheduler;

import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import com.marketingai.service.GateEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hourly scan: evaluate staged gates for all tenants' TEST campaigns.
 * Runs after MetaSyncScheduler has updated metrics.
 * Vision §12.3: fixed budget, gates = checkpoints, kill = pause.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GateScanScheduler {

    private final GateEvaluationService gateService;
    private final TenantRepository tenantRepo;

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 90_000)
    public void scanAll() {
        List<Tenant> tenants = tenantRepo.findAll();
        log.info("GateScanScheduler: scanning {} tenants", tenants.size());

        int totalEvaluated = 0;
        for (Tenant tenant : tenants) {
            try {
                int evaluated = gateService.evaluateAll(tenant.getId());
                if (evaluated > 0) {
                    log.info("GateScanScheduler: tenant {} — {} ad sets evaluated", tenant.getId(), evaluated);
                }
                totalEvaluated += evaluated;
            } catch (Exception e) {
                log.warn("GateScanScheduler: failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }

        log.info("GateScanScheduler: done — {} total ad sets evaluated", totalEvaluated);
    }
}
