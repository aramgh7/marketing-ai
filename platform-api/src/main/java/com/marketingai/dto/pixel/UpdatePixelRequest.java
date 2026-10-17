package com.marketingai.dto.pixel;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Data
public class UpdatePixelRequest {

    private String name;

    @Pattern(regexp = "PRIMARY|TEST|PRODUCT", message = "purpose must be PRIMARY, TEST, or PRODUCT")
    private String purpose;

    /** If provided, replaces the stored CAPI access token. */
    private String capiAccessToken;

    /** Replaces the full quality_filter JSONB when provided. */
    private Map<String, Object> qualityFilter;

    /** ACTIVE | ARCHIVED */
    @Pattern(regexp = "ACTIVE|ARCHIVED", message = "status must be ACTIVE or ARCHIVED")
    private String status;
}
