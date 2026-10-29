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
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    private Integer width;
    private Integer height;

    @Column(name = "duration_s")
    private Double durationS;

    @Column(name = "tags_l1", columnDefinition = "text[]")
    private String[] tagsL1 = new String[0];

    @Column(name = "tags_l2", columnDefinition = "text[]")
    private String[] tagsL2 = new String[0];

    @Column(name = "tags_l3", columnDefinition = "text[]")
    private String[] tagsL3 = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String autoTagSuggested;

    private Instant tagConfirmedAt;
    private UUID uploadedBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
