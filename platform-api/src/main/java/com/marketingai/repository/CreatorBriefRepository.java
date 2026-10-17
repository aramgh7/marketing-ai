package com.marketingai.repository;

import com.marketingai.entity.CreatorBrief;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreatorBriefRepository extends JpaRepository<CreatorBrief, UUID> {

    List<CreatorBrief> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<CreatorBrief> findByIdAndTenantId(UUID id, UUID tenantId);
}
