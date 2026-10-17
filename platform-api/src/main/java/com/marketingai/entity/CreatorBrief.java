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
@Table(name = "creator_briefs")
@Getter
@Setter
@NoArgsConstructor
public class CreatorBrief {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private String title;
    private String creativeType;

    @Column(columnDefinition = "text")
    private String targetAudience;

    @Column(columnDefinition = "text")
    private String productInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String recommendedAtoms = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String avoidAtoms = "[]";

    @Column(columnDefinition = "text")
    private String structureTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String benchmarks = "{}";

    @Column(columnDefinition = "text")
    private String notes;

    // data-driven brief enrichment (V88)
    @Column(columnDefinition = "text")
    private String vocSection;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String meta;

    @Column(nullable = false)
    private String status = "DRAFT";

    // vision §5.5.3: versioning, pairing, creator effect
    @Column(nullable = false)
    private int version = 1;

    private UUID resultCreativeId;
    private UUID pairedBriefId;

    @Column(nullable = false)
    private String briefType = "GENERAL";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
