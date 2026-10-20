package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors a Meta Ads Manager ad set.
 * Each ad set belongs to a campaign (contour).
 */
@Entity
@Table(name = "meta_ad_sets")
@Getter
@Setter
@NoArgsConstructor
public class MetaAdSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private UUID campaignId;

    /** Meta's own ad set ID */
    @Column(nullable = false)
    private String metaAdSetId;

    /** Meta's ad ID (the actual creative unit inside the ad set) */
    private String metaAdId;

    /** Our internal creative linked to this ad set */
    private UUID creativeId;

    /** Audience targeting spec as JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String audienceSpec;

    private Float dailyBudget;

    /** ACTIVE, PAUSED, ARCHIVED, DELETED */
    private String status;

    /** LEARNING, ACTIVE, LEARNING_LIMITED */
    private String learningPhase;

    private Float spendToday = 0f;
    private Float spendTotal = 0f;

    /** Groups ad sets launched together in a test batch */
    private UUID batchId;

    private Instant batchLaunchedAt;

    /** GATE_1 | GATE_2 | GATE_3 | PASSED | KILLED */
    @Column(nullable = false)
    private String gateStatus = "GATE_1";

    private String gateKillReason;
    private Instant gateKillAt;
    private Instant gate1EvaluatedAt;
    private Instant gate2EvaluatedAt;
    private Instant gate3EvaluatedAt;

    /** True if this ad set is the known-good anchor creative for Gate 3 comparison (§12.3) */
    @Column(nullable = false)
    private boolean isAnchor = false;

    /** True if this ad set occupies an exploration slot (new creative, wide prior) — §6.4 */
    @Column(nullable = false)
    private boolean isExplorationSlot = false;

    @Column(nullable = false)
    private Long impressionsTotal = 0L;

    /** Day-to-date snapshots from the last sync (V119) — needed to compute deltas (audit 6.3). */
    private Long impressionsToday = 0L;
    private Long clicksToday = 0L;
    private Long conversionsToday = 0L;

    @Column(nullable = false)
    private Long clicksTotal = 0L;

    @Column(nullable = false)
    private Long conversionsTotal = 0L;

    private Instant syncedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
