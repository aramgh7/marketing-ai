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
@Table(name = "meta_pixels")
@Getter
@Setter
@NoArgsConstructor
public class MetaPixel {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String pixelId;  // Meta pixel ID (numeric string)

    private String name;

    /** PRIMARY, TEST, PRODUCT */
    @Column(nullable = false)
    private String purpose = "PRIMARY";

    private UUID productId;

    @Column(name = "is_default")
    private boolean defaultPixel = false;

    /** CAPI access token — stored as raw bytes for Phase 0 (no encryption yet). */
    @Column(columnDefinition = "bytea")
    private byte[] capiAccessTokenEnc;

    /** Role key for CAPI v2 multi-pixel routing (investor_primary, future_regcf, etc.). */
    @Column(name = "role_key")
    private String roleKey = "investor_primary";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String qualityFilter = "{}";

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
