package com.marketingai.dto.pixel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.entity.MetaPixel;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Value
@Builder
@JsonIgnoreProperties({"default"})
public class PixelResponse {

    UUID id;
    String pixelId;
    String name;
    String purpose;
    @JsonProperty("isDefault")
    boolean isDefault;
    boolean hasCapiToken;   // never expose the actual token in responses
    Map<String, Object> qualityFilter;
    String status;
    Instant createdAt;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static PixelResponse from(MetaPixel p) {
        Map<String, Object> qf = parseQualityFilter(p.getQualityFilter());
        return PixelResponse.builder()
                .id(p.getId())
                .pixelId(p.getPixelId())
                .name(p.getName())
                .purpose(p.getPurpose())
                .isDefault(p.isDefaultPixel())
                .hasCapiToken(p.getCapiAccessTokenEnc() != null && p.getCapiAccessTokenEnc().length > 0)
                .qualityFilter(qf)
                .status(p.getStatus())
                .createdAt(p.getCreatedAt())
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseQualityFilter(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return Map.of();
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
