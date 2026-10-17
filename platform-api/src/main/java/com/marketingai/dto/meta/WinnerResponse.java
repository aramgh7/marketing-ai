package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Winner selection result from a TEST contour batch.
 * Includes shrinkage-adjusted score to mitigate winner's curse (§6.5).
 */
@Value
@Builder
public class WinnerResponse {
    UUID adSetId;
    String metaAdSetId;
    UUID creativeId;

    /** Raw Thompson alpha/(alpha+beta) estimate */
    double rawEstimate;

    /** James-Stein shrinkage-adjusted estimate (pulled toward group mean) */
    double shrunkEstimate;

    /** Total impressions seen */
    long impressions;

    /** Total spend so far */
    float spendTotal;

    /** Recommendation message for the marketer */
    String recommendation;
}
