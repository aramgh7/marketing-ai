package com.marketingai.repository;

import com.marketingai.entity.Creative;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreativeRepository extends JpaRepository<Creative, UUID> {

    List<Creative> findByTenantIdAndStatusNotOrderByCreatedAtDesc(UUID tenantId, String status);

    List<Creative> findByTenantIdAndTypeAndStatusNotOrderByCreatedAtDesc(UUID tenantId, String type, String status);

    Optional<Creative> findByIdAndTenantId(UUID id, UUID tenantId);
}
