package com.marketingai.repository;

import com.marketingai.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    boolean existsBySlug(String slug);
    Optional<Tenant> findBySlug(String slug);
}
