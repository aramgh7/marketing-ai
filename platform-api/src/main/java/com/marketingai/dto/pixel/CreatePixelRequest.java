package com.marketingai.dto.pixel;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Data
public class CreatePixelRequest {

    /** Meta numeric pixel ID (e.g. "123456789012345") */
    @NotBlank
    private String pixelId;

    private String name;

    /** PRIMARY | TEST | PRODUCT */
    @Pattern(regexp = "PRIMARY|TEST|PRODUCT", message = "purpose must be PRIMARY, TEST, or PRODUCT")
    private String purpose = "PRIMARY";

    /** Plain-text CAPI access token — stored as bytes (Phase 0; encrypted in Phase 1+). */
    private String capiAccessToken;

    /**
     * Quality filter: controls which GHL events qualify for CAPI forwarding on this pixel.
     * Example: {"require_crm_match": true, "allowed_events": ["appointment.completed", "opportunity.won"]}
     * Empty map = use platform defaults (§16.1 B2B mapping).
     */
    private Map<String, Object> qualityFilter;

    /** Set this pixel as the tenant default (unsets previous default). */
    @JsonProperty("isDefault")
    private boolean isDefault = false;
}
