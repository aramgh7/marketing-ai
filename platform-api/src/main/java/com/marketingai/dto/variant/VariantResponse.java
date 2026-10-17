package com.marketingai.dto.variant;

import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class VariantResponse {

    UUID id;
    UUID tenantId;
    UUID creativeId;
    String name;
    String slug;
    String status;

    @JsonRawValue
    String blocks;

    String[] blockOrder;

    Float matchScore;
    UUID approvedBy;
    Instant approvedAt;
    Instant createdAt;
    Instant updatedAt;

    VariantOfferResponse offer;

    // Thompson arm snapshot (null when arm not yet created)
    Float alpha;
    Float beta;
    Integer totalSamples;
}
