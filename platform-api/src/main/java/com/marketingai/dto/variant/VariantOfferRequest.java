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
}
