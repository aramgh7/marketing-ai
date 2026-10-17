package com.marketingai.repository;

import com.marketingai.entity.MetaCampaign;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaCampaignRepository extends JpaRepository<MetaCampaign, UUID> {

    List<MetaCampaign> findAllByTenantId(UUID tenantId);

    Optional<MetaCampaign> findByTenantIdAndContour(UUID tenantId, String contour);

    Optional<MetaCampaign> findByMetaCampaignId(String metaCampaignId);

    List<MetaCampaign> findAllByStatus(String status);
}
