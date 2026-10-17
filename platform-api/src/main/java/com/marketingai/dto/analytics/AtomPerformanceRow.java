package com.marketingai.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AtomPerformanceRow {

    String atomType;
    String atomValue;
    int creativeCount;
    long totalSessions;
    long conversions;
    Double conversionRate;
    Double avgRevenue;
}
