package com.marketingai.dto.meta;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class CampaignRequest {

    @NotBlank
    private String name;

    /** CONVERSIONS, LEAD_GENERATION, LINK_CLICKS */
    @NotBlank
    private String objective;

    /** TEST or SCALE */
    @NotNull
    private String contour;

    /** Daily budget in account currency (minor units — cents) */
    private Float dailyBudget;

    /** Optional pixel override — if null, the tenant's default pixel is used. */
    private UUID pixelId;
}
