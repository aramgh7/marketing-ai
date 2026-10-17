package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class AdSetResponse {
    UUID id;
    UUID campaignId;
    String metaAdSetId;
    String metaAdId;
    UUID creativeId;
    String audienceSpec;
    Float dailyBudget;
    String status;
    String learningPhase;
    Float spendToday;
    Float spendTotal;
    java.util.UUID batchId;
    Instant batchLaunchedAt;
    boolean isExplorationSlot;
    Instant syncedAt;
    Instant createdAt;
}
