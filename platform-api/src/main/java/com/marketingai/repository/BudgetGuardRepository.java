package com.marketingai.repository;

import com.marketingai.entity.BudgetGuard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetGuardRepository extends JpaRepository<BudgetGuard, UUID> {

    List<BudgetGuard> findByTenantId(UUID tenantId);

    Optional<BudgetGuard> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Used by scheduler — bypasses tenant filter (scheduler is privileged). */
    List<BudgetGuard> findAllByTriggeredFalse();
}
