package com.marketingai.dto.meta;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class AdSetRequest {

    /** Required for standalone POST /ad-sets; ignored in batch requests (campaign comes from path) */
    private UUID campaignId;

    @NotNull
    private UUID creativeId;

    /** Audience targeting spec as JSON string */
    private String audienceSpec;

    @NotBlank
    private String name;

    private Float dailyBudget;

    /** True if this ad set is an anchor (§11.2): known-good creative used as market-shock reference. */
    private boolean isAnchor = false;
}
