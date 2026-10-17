package com.marketingai.repository;

import com.marketingai.entity.CAPIEventLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CAPIEventLogRepository extends JpaRepository<CAPIEventLog, UUID> {

    List<CAPIEventLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, String status);
}
