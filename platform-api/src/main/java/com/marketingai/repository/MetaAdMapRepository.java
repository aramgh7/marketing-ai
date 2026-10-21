package com.marketingai.repository;

import com.marketingai.entity.MetaAdMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaAdMapRepository extends JpaRepository<MetaAdMap, UUID> {
    Optional<MetaAdMap> findByTenantIdAndMetaAdId(UUID tenantId, String metaAdId);
    Optional<MetaAdMap> findByIdAndTenantId(UUID id, UUID tenantId);
    List<MetaAdMap> findAllByTenantIdAndCreativeId(UUID tenantId, UUID creativeId);
    List<MetaAdMap> findAllByTenantIdAndStatus(UUID tenantId, String status);
    List<MetaAdMap> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<MetaAdMap> findAllByTenantId(UUID tenantId);
}
