package com.marketingai.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverviewResponse {

    int periodDays;
    long totalSessions;
    long totalConversions;
    Double conversionRate;
    double totalRevenue;
    long activeCreatives;
    long analyzedCreatives;
    String disclaimer;
}
