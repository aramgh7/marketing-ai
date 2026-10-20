package com.marketingai.entity;

import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.JdbcTypeCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Offer sub-atoms (§4.3a) for a landing variant:
 * discount, guarantee, bonus, urgency, payment terms.
 */
@Entity
@Table(name = "variant_offers")
@Getter
@Setter
@NoArgsConstructor
public class VariantOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID variantId;

    @Column(nullable = false)
    private UUID tenantId;

    /** percent | fixed | free_shipping | gift | none */
    private String discountType;

    /** 0.0–1.0 normalised depth */
    private Float discountDepth;

    /** money_back_14 | money_back_30 | lifetime | none */
    private String guaranteeType;

    private Integer guaranteeDays;

    private String bonus;

    private Boolean hasRealUrgency = false;

    private String urgencySource;

    private Integer urgencyDaysLeft;

    /** full | installment_3 | installment_6 | trial | freemium */
    private String paymentTerms;

    private Float marginFloor;
    private Float estimatedMargin;
    private Boolean requiresLegalReview = false;

    /** Roadmap 8-8: offer atom schema key (INVESTOR_RAISE, …); null = legacy B2B columns */
    private String schemaKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String atoms;

    private String offerText;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
