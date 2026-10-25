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

    @Column(nullable = false)
    private String s3Key;

    @Column(nullable = false)
    private String mimeType;

    private Integer width;
    private Integer height;
    private Double durationS;

    @Column(columnDefinition = "text[]")
    private String[] tagsL1 = new String[0];

    @Column(columnDefinition = "text[]")
    private String[] tagsL2 = new String[0];

    @Column(columnDefinition = "text[]")
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
