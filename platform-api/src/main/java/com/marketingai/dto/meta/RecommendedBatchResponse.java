package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

/**
 * Recommended next test batch for a campaign.
 * Split into exploitation (EVI-ranked) and exploration (new/untested) slots.
 * Vision §6.4.
 */
@Value
@Builder
public class RecommendedBatchResponse {

    UUID campaignId;
    int batchSize;

    /** Fraction reserved for exploration (from campaign.explorationRate) */
    float explorationRate;

    int exploitationSlots;
    int explorationSlots;

    /** EVI-ranked existing creatives for exploitation slots */
    List<CandidateSlot> exploitationCandidates;

    /** New/untested creatives for exploration slots */
    List<CandidateSlot> explorationCandidates;

    @Value
    @Builder
    public static class CandidateSlot {
        UUID creativeId;
        String creativeTitle;
        String creativeType;

        /** Top-Two Thompson EVI score (0-1). Higher = more informative to test */
        double eviScore;

        /** Current Thompson posterior alpha */
        double priorAlpha;

        /** Current Thompson posterior beta */
        double priorBeta;

        /** Number of observations so far */
        long observations;

        /** True if this is an unexplored creative (exploration slot) */
        boolean isNew;
    }
}
