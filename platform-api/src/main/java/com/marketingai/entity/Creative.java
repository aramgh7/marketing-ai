package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "creatives")
@Getter
@Setter
@NoArgsConstructor
public class Creative {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Meta creative ID (if synced from Meta) */
    private String externalId;

    /** VIDEO, IMAGE, CAROUSEL */
    @Column(nullable = false)
    private String type;

    private String title;

    /** Storage URL: /files/... (local) or R2 URL (Phase 4) */
    private String fileUrl;

    private String thumbnailUrl;

    /** Whisper transcript for video creatives */
    @Column(columnDefinition = "text")
    private String transcript;

    private Float durationSec;

    /** UPLOADED, ANALYZING, ACTIVE, ARCHIVED */
    @Column(nullable = false)
    private String status = "UPLOADED";

    /** Anchor creative for market effect isolation (§11.2) */
    @Column(nullable = false)
    private boolean isAnchor = false;

    /**
     * Optimistic prior for Thompson arm (§6.4).
     * Default Beta(2,1): slightly optimistic, wide uncertainty → gets exploration traffic.
     * Set lower (e.g. 1.0/2.0) for creatives with known negative genome signals.
     */
    @Column(nullable = false)
    private Float explorationPriorAlpha = 2.0f;

    @Column(nullable = false)
    private Float explorationPriorBeta = 1.0f;

    private String metaAdSetId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
