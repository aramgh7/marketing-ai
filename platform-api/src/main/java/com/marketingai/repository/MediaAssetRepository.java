package com.marketingai.repository;

import com.marketingai.entity.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<MediaAsset> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Assets that have any overlap with the given L2 role tag (native query for PostgreSQL array operator). */
    @Query(value = "SELECT * FROM assets WHERE tenant_id = :tenantId AND tags_l2 && ARRAY[:tag]::text[] ORDER BY created_at DESC", nativeQuery = true)
    List<MediaAsset> findByTenantIdAndTagsL2Contains(@Param("tenantId") UUID tenantId, @Param("tag") String tag);

    long countByTenantId(UUID tenantId);
}
