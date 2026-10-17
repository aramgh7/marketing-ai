package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String niche;
    private String metaAppId;

    @Column(columnDefinition = "bytea")
    private byte[] metaAccessTokenEnc;

    /** Meta Ads account ID, e.g. "act_123456789" — added in V16 migration */
    private String metaAdAccountId;

    /** vision §21.5: holdout group for System vs Baseline Benchmark (default ON, 4%) */
    @Column(nullable = false)
    private boolean holdoutEnabled = true;

    @Column(nullable = false, precision = 4, scale = 3)
    private java.math.BigDecimal holdoutRate = new java.math.BigDecimal("0.040");

    @Column(columnDefinition = "jsonb")
    private String settings = "{}";

    private String reportEmail;

    @Column(length = 10)
    private String alertChannel = "TELEGRAM";

    @Column(length = 500)
    private String slackWebhookUrl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

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
