package com.marketingai.repository;

import com.marketingai.entity.CircuitBreakerEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CircuitBreakerEventRepository extends JpaRepository<CircuitBreakerEvent, UUID> {

    /** Active (unresolved) events for a tenant */
    List<CircuitBreakerEvent> findByTenantIdAndResolvedAtIsNull(UUID tenantId);

    /** All events for a tenant since a given time */
    List<CircuitBreakerEvent> findByTenantIdAndCreatedAtAfter(UUID tenantId, Instant since);

    /** L1 events in last 24h (for L1→L2 cascade check) */
    List<CircuitBreakerEvent> findByTenantIdAndLevelAndCreatedAtAfter(UUID tenantId, String level, Instant since);
}
