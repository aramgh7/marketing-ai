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
 * Roadmap Phase 11 (.ai/creative-publishing.md): one row per Meta ad — which of our creatives it carries,
 * the landing entry slug and the destination URL / url_tags the platform set. Identity of the arm is our
 * creative, never the Meta ad.
 */
@Entity
@Table(name = "meta_ad_map")
@Getter
@Setter
@NoArgsConstructor
public class MetaAdMap {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String metaAdId;

    private String metaAdsetId;
    private String metaCampaignId;
    private String metaCreativeId;
    private String metaVideoId;
    private String metaImageHash;

    private UUID creativeId;
    private String entrySlug;

    @Column(columnDefinition = "text")
    private String destinationUrl;

    @Column(columnDefinition = "text")
    private String urlTags;

    private String adName;

    /** MANAGED (published from the cabinet) | RECONCILED (read back from Meta) | MANUAL (linked by a human) */
    @Column(nullable = false)
    private String source = "MANAGED";

    /** PAUSED | PENDING_REVIEW | APPROVED | ACTIVE | REJECTED | ARCHIVED | UNMANAGED | DRIFT | UNKNOWN */
    @Column(nullable = false)
    private String status = "PAUSED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String drift;

    private Instant driftDetectedAt;
    private Instant lastSeenAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
