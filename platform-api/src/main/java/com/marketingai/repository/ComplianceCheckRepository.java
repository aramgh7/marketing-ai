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

    @Query("""
        SELECT c FROM ComplianceCheck c
        WHERE c.tenantId = :tenantId AND c.entityId = :entityId
        ORDER BY c.createdAt DESC
        LIMIT 1
        """)
    Optional<ComplianceCheck> findLatestByEntityId(@Param("tenantId") UUID tenantId,
                                                    @Param("entityId") UUID entityId);
}
