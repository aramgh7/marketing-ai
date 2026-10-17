package com.marketingai.repository;

import com.marketingai.entity.ThompsonArm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ThompsonArmRepository extends JpaRepository<ThompsonArm, UUID> {

    Optional<ThompsonArm> findByVariantIdAndContextKey(UUID variantId, String contextKey);

    /** Batch load — avoids N+1 in listVariants */
    List<ThompsonArm> findByVariantIdInAndContextKey(List<UUID> variantIds, String contextKey);

    List<ThompsonArm> findAllByTenantId(UUID tenantId);
}
