package com.marketingai.repository;

import com.marketingai.entity.TenantDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantDomainRepository extends JpaRepository<TenantDomain, UUID> {

    Optional<TenantDomain> findByHost(String host);

    List<TenantDomain> findAllByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    List<TenantDomain> findAllByStatus(String status);

    Optional<TenantDomain> findByIdAndTenantId(UUID id, UUID tenantId);
}
