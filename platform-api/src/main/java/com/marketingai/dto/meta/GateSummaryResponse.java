package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

@Value
@Builder
public class GateSummaryResponse {
    UUID campaignId;
    int total;
    int atGate1;
    int atGate2;
    int atGate3;
    int passed;
    int killed;
    List<GateStatusResponse> adSets;
}
