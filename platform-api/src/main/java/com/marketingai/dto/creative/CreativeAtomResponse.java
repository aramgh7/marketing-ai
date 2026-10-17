package com.marketingai.dto.creative;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class CreativeAtomResponse {
    UUID id;
    UUID creativeId;
    String atomType;
    String atomValue;
    Float confidence;
    String extractedBy;
    Instant createdAt;
}
