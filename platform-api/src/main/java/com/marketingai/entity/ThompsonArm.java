package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Beta(α,β) posterior for one landing variant arm.
 * Created when a variant is approved (status → ACTIVE).
 * α = successes+1 (Beta prior), β = failures+1.
 */
@Entity
@Table(name = "thompson_arms")
@Getter
@Setter
@NoArgsConstructor
public class ThompsonArm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private String contextKey = "global";

    /** arch v1.1 §4.4: event type this arm counts (binary; delta always 1.0) */
    @Column(nullable = false)
    private String rewardEvent = "FORM_SUBMIT";

    @Column(nullable = false)
    private Float alpha = 1.0f;

    @Column(nullable = false)
    private Float beta = 1.0f;

    private Float revenueMu = 0.0f;
    private Float revenueSigma = 1.0f;
    private Integer revenueN = 0;
    private Integer totalSamples = 0;

    private Instant lastDecayedAt;
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        lastDecayedAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
