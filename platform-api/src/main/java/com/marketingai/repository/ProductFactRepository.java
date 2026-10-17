package com.marketingai.repository;

import com.marketingai.entity.ProductFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductFactRepository extends JpaRepository<ProductFact, UUID> {

    List<ProductFact> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<ProductFact> findByIdAndTenantId(UUID id, UUID tenantId);
}
