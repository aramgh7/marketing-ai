package com.marketingai.service;

import com.marketingai.dto.budget.*;
import com.marketingai.entity.BudgetGuard;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.BudgetGuardRepository;
import com.marketingai.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetGuardService {

    private final BudgetGuardRepository guardRepo;
    private final TenantRepository tenantRepo;
    private final AlertService alertService;

    private static final List<String> VALID_TYPES =
            List.of("PER_CREATIVE_STOP_LOSS", "MONTHLY_CEILING");

    @Transactional
    public BudgetGuardResponse create(UUID tenantId, CreateGuardRequest req) {
        if (!VALID_TYPES.contains(req.getGuardType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "guardType must be PER_CREATIVE_STOP_LOSS or MONTHLY_CEILING");
        }
        BudgetGuard guard = new BudgetGuard();
        guard.setTenantId(tenantId);
        guard.setGuardType(req.getGuardType());
        guard.setReferenceId(req.getReferenceId());
        guard.setThreshold(req.getThreshold());
        return BudgetGuardResponse.from(guardRepo.save(guard));
    }

    public List<BudgetGuardResponse> list(UUID tenantId) {
        return guardRepo.findByTenantId(tenantId)
                .stream()
                .map(BudgetGuardResponse::from)
                .toList();
    }

    public BudgetGuardResponse get(UUID tenantId, UUID guardId) {
        return BudgetGuardResponse.from(findOwned(tenantId, guardId));
    }

    @Transactional
    public BudgetGuardResponse update(UUID tenantId, UUID guardId, UpdateGuardRequest req) {
        BudgetGuard guard = findOwned(tenantId, guardId);

        if (req.getThreshold() != null) {
            guard.setThreshold(req.getThreshold());
        }
        if (Boolean.TRUE.equals(req.getResetTrigger())) {
            guard.setTriggered(false);
            guard.setTriggeredAt(null);
            log.info("Guard {} manually reset by tenant {}", guardId, tenantId);
        }
        return BudgetGuardResponse.from(guardRepo.save(guard));
    }

    @Transactional
    public void delete(UUID tenantId, UUID guardId) {
        BudgetGuard guard = findOwned(tenantId, guardId);
        guardRepo.delete(guard);
    }

    /**
     * Manual spend update — marketer enters current spend from Meta Ads Manager.
     * Immediately evaluates the guard after updating.
     */
    @Transactional
    public BudgetGuardResponse updateSpend(UUID tenantId, UUID guardId, UpdateSpendRequest req) {
        BudgetGuard guard = findOwned(tenantId, guardId);
        guard.setCurrentSpend(req.getCurrentSpend());
        guardRepo.save(guard);

        if (!guard.isTriggered() && guard.getCurrentSpend() >= guard.getThreshold()) {
            triggerGuard(guard);
        }
        return BudgetGuardResponse.from(guard);
    }

    /**
     * Called by CircuitBreakerScheduler every 5 min.
     * Checks all non-triggered guards across all tenants.
     */
    @Transactional
    public void checkAllGuards() {
        List<BudgetGuard> candidates = guardRepo.findAllByTriggeredFalse();

        for (BudgetGuard guard : candidates) {
            // Monthly ceiling: reset if we've entered a new billing month
            if ("MONTHLY_CEILING".equals(guard.getGuardType())) {
                LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
                if (guard.getPeriodStart() == null || guard.getPeriodStart().isBefore(firstOfMonth)) {
                    guard.setPeriodStart(firstOfMonth);
                    guard.setCurrentSpend(0f);
                    guardRepo.save(guard);
                    continue; // reset — don't evaluate this cycle
                }
            }

            if (guard.getCurrentSpend() >= guard.getThreshold()) {
                triggerGuard(guard);
                guardRepo.save(guard);
            }
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private void triggerGuard(BudgetGuard guard) {
        guard.setTriggered(true);
        guard.setTriggeredAt(Instant.now());
        log.warn("Budget guard triggered: id={} tenant={} type={} spend={}/{}",
                guard.getId(), guard.getTenantId(), guard.getGuardType(),
                guard.getCurrentSpend(), guard.getThreshold());

        tenantRepo.findById(guard.getTenantId()).ifPresent(tenant -> {
            String type = "PER_CREATIVE_STOP_LOSS".equals(guard.getGuardType())
                    ? "Per-Creative Stop-Loss" : "Monthly Ceiling";
            String ref = guard.getReferenceId() != null
                    ? "Creative ID: " + guard.getReferenceId() : "Account-level";
            alertService.sendAlert(String.format(
                    "🚨 Budget Guard Triggered — %s\n\nType: %s\n%s\n" +
                    "Threshold: $%.2f\nCurrent Spend: $%.2f\n\n" +
                    "Action Required: Pause affected ad set(s) in Meta Ads Manager.",
                    tenant.getName(), type, ref,
                    guard.getThreshold(), guard.getCurrentSpend()));
        });
    }

    private BudgetGuard findOwned(UUID tenantId, UUID guardId) {
        return guardRepo.findByIdAndTenantId(guardId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Budget guard not found"));
    }
}
