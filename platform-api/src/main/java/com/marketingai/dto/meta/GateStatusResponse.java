package com.marketingai.dto.meta;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class GateStatusResponse {
    UUID adSetId;
    String metaAdSetId;
    UUID creativeId;

    /** GATE_1 | GATE_2 | GATE_3 | PASSED | KILLED */
    String gateStatus;

    String killReason;
    Instant killAt;

    long impressionsTotal;
    long clicksTotal;
    long conversionsTotal;
    float spendTotal;

    boolean isAnchor;

    Instant gate1EvaluatedAt;
    Instant gate2EvaluatedAt;
    Instant gate3EvaluatedAt;
    Instant batchLaunchedAt;
}
