package com.marketingai.repository;

import com.marketingai.entity.MetaPixel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaPixelRepository extends JpaRepository<MetaPixel, UUID> {

    List<MetaPixel> findByTenantIdAndStatus(UUID tenantId, String status);

    Optional<MetaPixel> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Returns the Meta pixel_id string for the default active pixel of a tenant. */
    @Query("SELECT p.pixelId FROM MetaPixel p " +
           "WHERE p.tenantId = :tenantId AND p.defaultPixel = true AND p.status = 'ACTIVE'")
    Optional<String> findDefaultPixelId(UUID tenantId);

    /**
     * Returns the sealed CAPI access token for the default pixel — open it with {@code TokenVault} (roadmap 9-1).
     */
    @Query("SELECT p.capiAccessTokenEnc FROM MetaPixel p " +
           "WHERE p.tenantId = :tenantId AND p.defaultPixel = true AND p.status = 'ACTIVE'")
    Optional<byte[]> findDefaultCapiTokenBytes(UUID tenantId);

    /** Find pixel_id by role_key (for CAPI v2 multi-pixel routing). */
    @Query("SELECT p.pixelId FROM MetaPixel p " +
           "WHERE p.tenantId = :tenantId AND p.roleKey = :roleKey AND p.status = 'ACTIVE' LIMIT 1")
    Optional<String> findPixelIdByRole(UUID tenantId, String roleKey);

    /** Find CAPI token by role_key. */
    @Query("SELECT p.capiAccessTokenEnc FROM MetaPixel p " +
           "WHERE p.tenantId = :tenantId AND p.roleKey = :roleKey AND p.status = 'ACTIVE' LIMIT 1")
    Optional<byte[]> findCapiTokenBytesByRole(UUID tenantId, String roleKey);
}
