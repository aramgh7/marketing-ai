package com.marketingai.repository;

import com.marketingai.entity.ComplianceCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComplianceCheckRepository extends JpaRepository<ComplianceCheck, UUID> {

    List<ComplianceCheck> findByTenantIdAndEntityIdOrderByCreatedAtDesc(UUID tenantId, UUID entityId);

    /** Latest check for an entity. JPQL has no LIMIT (Hibernate 6 rejects it at boot) — derived query instead. */
    Optional<ComplianceCheck> findFirstByTenantIdAndEntityIdOrderByCreatedAtDesc(UUID tenantId, UUID entityId);

    default Optional<ComplianceCheck> findLatestByEntityId(UUID tenantId, UUID entityId) {
        return findFirstByTenantIdAndEntityIdOrderByCreatedAtDesc(tenantId, entityId);
    }
}
