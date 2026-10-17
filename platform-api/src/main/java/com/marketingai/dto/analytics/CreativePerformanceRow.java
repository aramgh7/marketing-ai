package com.marketingai.dto.analytics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties({"anchor"})
public class CreativePerformanceRow {

    UUID creativeId;
    String title;
    String type;
    String status;
    @JsonProperty("isAnchor") boolean isAnchor;
    int atomsCount;
    long sessions;
    long conversions;
    Double conversionRate;
    double revenue;

    // Phase 3 — populated once Meta Marketing API is integrated
    Double spend;
    Double roas;
    Long impressions;
    Double ctr;
}
