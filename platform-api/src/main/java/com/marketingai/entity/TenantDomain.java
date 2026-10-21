package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Roadmap Phase 10: a client domain that serves landings — either a CNAME to our landing edge or a
 * self-hosted landing runner on the client's own server. Verified by DNS TXT (+ CNAME for CNAME hosting).
 */
@Entity
@Table(name = "tenant_domains")
@Getter
@Setter
@NoArgsConstructor
public class TenantDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    /** Lower-case FQDN, globally unique. */
    @Column(nullable = false)
    private String host;

    /** LANDING | COLLECT */
    @Column(nullable = false)
    private String kind = "LANDING";

    /** CNAME | SELF_HOSTED */
    @Column(nullable = false)
    private String hosting = "CNAME";

    /** Slug served at "/" of this host (optional). */
    private String defaultSlug;

    @Column(nullable = false)
    private String verificationToken;

    /** PENDING | VERIFIED | FAILED | DISABLED */
    @Column(nullable = false)
    private String status = "PENDING";

    private Instant verifiedAt;
    private Instant lastCheckAt;
    private String lastError;

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
