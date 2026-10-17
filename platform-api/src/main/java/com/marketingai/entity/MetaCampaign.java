package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors a Meta Ads Manager campaign.
 * contour = TEST (isolated ad sets, equal budgets) or SCALE (CBO / Advantage+) — §6.5
 */
@Entity
@Table(name = "meta_campaigns")
@Getter
@Setter
@NoArgsConstructor
public class MetaCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID pixelId;

    /** Meta's own campaign ID (e.g. "120210000000000") */
    @Column(nullable = false)
    private String metaCampaignId;

    private String name;

    /** CONVERSIONS, LEAD_GENERATION, LINK_CLICKS, etc. */
    private String objective;

    /** TEST (exploration) or SCALE (exploitation) — vision §6.5 */
    @Column(nullable = false)
    private String contour = "TEST";

    /** Max spend per ad set before L1 circuit breaker pauses it (§12.3, §12.5a) */
    private Float maxSpendPerAdset;

    /** Monthly budget ceiling — L3 triggers when total spend reaches this (§12.5a) */
    private Float monthlyCeilingBudget;

    /** Target CPA for L2 Bayesian trigger (§12.5a) */
    private Float targetCpa;

    /** Breakeven ROAS for L3 Bayesian trigger (§12.5a) */
    private Float breakevenRoas;

    /** Fraction of batch slots reserved for unexplored creatives (§6.4). Default 0.15 = 15% */
    @Column(nullable = false)
    private Float explorationRate = 0.15f;

    private Float dailyBudget;

    /** ACTIVE, PAUSED, ARCHIVED, DELETED */
    private String status;

    /** LEARNING, ACTIVE, LEARNING_LIMITED — Meta's learning phase status */
    private String learningPhase;

    private Float spendToday = 0f;
    private Float spendTotal = 0f;

    /**
     * Meta ad set ID of the confirmation run ad set.
     * Populated when confirmation run starts; null before that.
     */
    private String confirmationAdSetId;

    /**
     * NONE → RUNNING → PASSED / FAILED
     * Tracks confirmation run lifecycle before transfer to SCALE.
     */
    @Column(nullable = false)
    private String confirmationStatus = "NONE";

    private Instant syncedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
