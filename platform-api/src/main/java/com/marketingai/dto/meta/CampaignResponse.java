package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class CampaignResponse {
    UUID id;
    UUID tenantId;
    String metaCampaignId;
    String name;
    String objective;
    String contour;
    Float dailyBudget;
    String status;
    String learningPhase;
    Float spendToday;
    Float spendTotal;
    String confirmationAdSetId;
    String confirmationStatus;
    Instant syncedAt;
    Instant createdAt;
}
