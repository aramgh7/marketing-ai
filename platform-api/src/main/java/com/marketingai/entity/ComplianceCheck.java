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
@Table(name = "compliance_checks")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** LANDING_VARIANT, CREATIVE, BRIEF, OFFER */
    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private UUID entityId;

    /** META_POLICY, CLAIMS, TRADEMARK, FACE, MUSIC, AI_LABEL */
    @Column(nullable = false)
    private String checkType;

    /** PASS, WARN, FAIL */
    @Column(nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String findings;

    private String modelVersion;

    private UUID overriddenBy;
    private String overrideReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
