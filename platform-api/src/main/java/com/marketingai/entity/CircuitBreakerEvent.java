package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Records each circuit breaker trigger event (L1/L2/L3).
 * An unresolved event (resolvedAt = null) means the breaker is still active.
 * Vision §12.5a.
 */
@Entity
@Table(name = "circuit_breaker_events")
@Getter
@Setter
@NoArgsConstructor
public class CircuitBreakerEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** L1, L2, L3 */
    @Column(nullable = false)
    private String level;

    @Column(nullable = false)
    private String triggerReason;

    /** Bayesian posterior probability that caused the trigger */
    private Float bayesianProb;

    /** TEST | SCALE | ALL */
    private String contour;

    /** PAUSE_ADSET | THROTTLE_TEST | PAUSE_SCALE | PAUSE_ALL */
    @Column(nullable = false)
    private String actionTaken;

    /** null = still active */
    private Instant resolvedAt;
    private String resolvedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
