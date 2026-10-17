package com.marketingai.repository;

import com.marketingai.entity.MetaAdSet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetaAdSetRepository extends JpaRepository<MetaAdSet, UUID> {

    List<MetaAdSet> findAllByCampaignId(UUID campaignId);

    List<MetaAdSet> findAllByTenantId(UUID tenantId);

    Optional<MetaAdSet> findByMetaAdSetId(String metaAdSetId);

    List<MetaAdSet> findAllByCampaignIdAndStatus(UUID campaignId, String status);

    List<MetaAdSet> findAllByCampaignIdAndBatchId(UUID campaignId, UUID batchId);
}
