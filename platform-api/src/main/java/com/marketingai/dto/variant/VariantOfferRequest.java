package com.marketingai.dto.variant;

import lombok.Data;

@Data
public class VariantOfferRequest {
    private String discountType;
    private Float discountDepth;
    private String guaranteeType;
    private Integer guaranteeDays;
    private String bonus;
    private Boolean hasRealUrgency;
    private String urgencySource;
    private Integer urgencyDaysLeft;
    private String paymentTerms;
    private Float marginFloor;
    private Float estimatedMargin;

    // Roadmap 8-8: schema-based offers (tenants.settings.offer_schema, e.g. INVESTOR_RAISE)
    private String schemaKey;
    private java.util.Map<String, Object> atoms;
    /** Text the compliance gate checks when the schema's validation_mode is COMPLIANCE_PASS */
    private String offerText;
}
