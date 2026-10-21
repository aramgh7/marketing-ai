package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "landing_variants")
@Getter
@Setter
@NoArgsConstructor
public class LandingVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID creativeId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    /** DRAFT → REVIEW → ACTIVE, also PAUSED, ARCHIVED */
    @Column(nullable = false)
    private String status = "DRAFT";

    /** Ordered array of block objects: [{type,id,data:{...}}, ...] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String blocks = "[]";

    /**
     * Ordered array of block type keys used for rendering order.
     * Separate from block content — enables block reordering without content changes (Phase 5).
     */
    @Column(name = "block_order", columnDefinition = "text[]")
    private String[] blockOrder = new String[0];

    /** LLM-computed message-match score (0–1). Null until computed. */
    private Float matchScore;

    private UUID approvedBy;
    private Instant approvedAt;

    /** Roadmap Phase 10: variants generated together from one brief share a set_id (compare side by side). */
    private UUID setId;
    /** Roadmap Phase 10: why a marketer rejected this variant (feeds RLHA). */
    private String rejectReason;
    private String rejectNote;
    /** Roadmap Phase 10: the style angle that produced this variant. */
    private String styleAngle;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
