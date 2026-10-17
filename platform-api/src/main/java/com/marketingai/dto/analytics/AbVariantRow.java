package com.marketingai.dto.analytics;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class AbVariantRow {
    UUID variantId;
    String name;
    String slug;
    String status;
    double alpha;
    double beta;
    int totalSamples;
    double estimatedCvr;
    long sessions;
    long conversions;
    Double observedCvr;
    double probBest;
}
