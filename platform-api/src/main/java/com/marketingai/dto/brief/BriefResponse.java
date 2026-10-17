package com.marketingai.dto.brief;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BriefResponse {

    UUID id;
    UUID tenantId;
    String title;
    String creativeType;
    String targetAudience;
    String productInfo;

    /** Raw JSON array: [{atomType, atomValue, cvr, creativeCount, rationale}] */
    @JsonRawValue
    String recommendedAtoms;

    /** Raw JSON array: low-performing atoms to avoid */
    @JsonRawValue
    String avoidAtoms;

    String structureTemplate;

    /** Raw JSON: {cvrAvg, topHook, ...} */
    @JsonRawValue
    String benchmarks;

    String notes;
    String status;
    int version;
    String briefType;
    UUID pairedBriefId;
    UUID resultCreativeId;
    String markdownExport;
    Instant createdAt;
    Instant updatedAt;
}
