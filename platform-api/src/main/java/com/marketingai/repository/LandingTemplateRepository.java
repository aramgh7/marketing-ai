package com.marketingai.repository;

import com.marketingai.entity.LandingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LandingTemplateRepository extends JpaRepository<LandingTemplate, UUID> {

    /** All templates visible to a tenant: platform defaults (tenant_id IS NULL) + tenant's own. */
    @Query("SELECT t FROM LandingTemplate t WHERE t.tenantId IS NULL OR t.tenantId = :tenantId ORDER BY t.createdAt ASC")
    List<LandingTemplate> findVisibleToTenant(@Param("tenantId") UUID tenantId);

    /** Templates owned by this tenant only (excludes platform defaults). */
    List<LandingTemplate> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    Optional<LandingTemplate> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Platform defaults only. */
    @Query("SELECT t FROM LandingTemplate t WHERE t.tenantId IS NULL ORDER BY t.createdAt ASC")
    List<LandingTemplate> findPlatformDefaults();
}
