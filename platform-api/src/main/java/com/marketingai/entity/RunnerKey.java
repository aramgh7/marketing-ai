package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Roadmap Phase 10: a key for the self-hosted landing runner. Only the SHA-256 of the key is stored;
 * the plaintext is shown once when issued.
 */
@Entity
@Table(name = "runner_keys")
@Getter
@Setter
@NoArgsConstructor
public class RunnerKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private UUID domainId;

    @Column(nullable = false)
    private String keyHash;

    private String label;

    private Instant lastSeenAt;
    private Instant revokedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
