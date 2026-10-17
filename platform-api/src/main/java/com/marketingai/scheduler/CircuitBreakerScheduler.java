package com.marketingai.scheduler;

import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import com.marketingai.service.BudgetGuardService;
import com.marketingai.service.CircuitBreakerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerScheduler {

    private final BudgetGuardService guardService;
    private final CircuitBreakerService cbService;
    private final TenantRepository tenantRepo;

    /** Checks all budget guards across all tenants every 5 minutes. */
    @Scheduled(fixedDelay = 300_000)
    public void checkBudgetGuards() {
        log.debug("Circuit breaker check started");
        guardService.checkAllGuards();
        log.debug("Circuit breaker check completed");
    }

    /**
     * L2/L3 Meta Circuit Breaker check every 30 minutes (§12.5a).
     * Runs after MetaSyncScheduler has refreshed spend/metrics.
     */
    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 120_000)
    public void checkMetaCircuitBreaker() {
        log.info("Meta CircuitBreaker check started");
        for (Tenant tenant : tenantRepo.findAll()) {
            try {
                cbService.checkAll(tenant.getId());
            } catch (Exception e) {
                log.warn("Meta CB check failed for tenant {}: {}", tenant.getId(), e.getMessage());
            }
        }
        log.info("Meta CircuitBreaker check done");
    }
}
