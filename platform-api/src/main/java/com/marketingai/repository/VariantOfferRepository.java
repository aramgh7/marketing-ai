package com.marketingai.repository;

import com.marketingai.entity.VariantOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VariantOfferRepository extends JpaRepository<VariantOffer, UUID> {

    Optional<VariantOffer> findByVariantIdAndTenantId(UUID variantId, UUID tenantId);

    /** Batch load — avoids N+1 in listVariants */
    List<VariantOffer> findByVariantIdInAndTenantId(List<UUID> variantIds, UUID tenantId);

    void deleteByVariantIdAndTenantId(UUID variantId, UUID tenantId);
}
