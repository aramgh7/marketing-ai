package com.marketingai.repository;

import java.time.Instant;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import com.marketingai.entity.CAPIEventLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CAPIEventLogRepository extends JpaRepository<CAPIEventLog, UUID> {

    List<CAPIEventLog> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    long countByTenantIdAndStatus(UUID tenantId, String status);

    /** PENDING/FAILED rows still inside Meta's 7-day window with retries left (audit 4.5). */
    @Query("SELECT c FROM CAPIEventLog c WHERE c.status IN ('PENDING','FAILED') " +
           "AND c.createdAt > :since AND c.attempts < :maxAttempts ORDER BY c.createdAt ASC")
    List<CAPIEventLog> findRetryable(@Param("since") Instant since, @Param("maxAttempts") int maxAttempts, Pageable pageable);
}
