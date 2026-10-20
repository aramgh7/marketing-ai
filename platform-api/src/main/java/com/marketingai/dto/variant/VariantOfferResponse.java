package com.marketingai.dto.variant;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class VariantOfferResponse {
    UUID id;
    String discountType;
    Float discountDepth;
    String guaranteeType;
    Integer guaranteeDays;
    String bonus;
    Boolean hasRealUrgency;
    String urgencySource;
    Integer urgencyDaysLeft;
    String paymentTerms;
    Float marginFloor;
    String schemaKey;
    java.util.Map<String, Object> atoms;
    String offerText;
}
