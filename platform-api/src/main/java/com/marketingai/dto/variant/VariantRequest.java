package com.marketingai.dto.variant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

@Data
public class VariantRequest {

    @NotBlank
    private String name;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "slug: lowercase letters, digits, hyphens only")
    private String slug;

    private UUID creativeId;

    /** JSON array of block objects — validated loosely (not null) */
    private String blocks = "[]";

    /** Ordered array of block type keys (e.g. ["HERO","PROOF","CTA"]). Optional. */
    private String[] blockOrder;

    private VariantOfferRequest offer;

    /** Roadmap Phase 10: optional set grouping / style angle. */
    private UUID setId;
    private String styleAngle;
}
