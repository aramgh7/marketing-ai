package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "budget_guards")
@Getter
@Setter
@NoArgsConstructor
public class BudgetGuard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** PER_CREATIVE_STOP_LOSS or MONTHLY_CEILING */
    @Column(nullable = false)
    private String guardType;

    /** creative_id for PER_CREATIVE_STOP_LOSS, null for MONTHLY_CEILING */
    private UUID referenceId;

    @Column(nullable = false)
    private float threshold;

    @Column(nullable = false)
    private float currentSpend = 0f;

    @Column(name = "is_triggered", nullable = false)
    private boolean triggered = false;

    private Instant triggeredAt;

    /** First day of billing period (MONTHLY_CEILING only) */
    private LocalDate periodStart;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if ("MONTHLY_CEILING".equals(guardType) && periodStart == null) {
            this.periodStart = LocalDate.now().withDayOfMonth(1);
        }
    }
}
