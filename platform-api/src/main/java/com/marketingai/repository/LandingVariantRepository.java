package com.marketingai.repository;

import com.marketingai.entity.LandingVariant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LandingVariantRepository extends JpaRepository<LandingVariant, UUID> {

    List<LandingVariant> findAllByTenantId(UUID tenantId);

    List<LandingVariant> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<LandingVariant> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Used by public landing endpoint — slug should be globally unique in practice. */
    Optional<LandingVariant> findBySlugAndStatus(String slug, String status);

    Optional<LandingVariant> findBySlug(String slug);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);
}
