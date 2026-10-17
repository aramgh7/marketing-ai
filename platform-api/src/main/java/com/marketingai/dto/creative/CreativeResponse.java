package com.marketingai.dto.creative;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Value
@Builder
@JsonIgnoreProperties({"anchor"})
public class CreativeResponse {
    UUID id;
    UUID tenantId;
    String externalId;
    String type;
    String title;
    String fileUrl;
    String thumbnailUrl;
    String transcript;
    Float durationSec;
    String status;

    @JsonProperty("isAnchor")
    boolean isAnchor;

    String metaAdSetId;
    Instant createdAt;

    /** Included only on detail endpoint (GET /creatives/{id}) */
    List<CreativeAtomResponse> atoms;
}
