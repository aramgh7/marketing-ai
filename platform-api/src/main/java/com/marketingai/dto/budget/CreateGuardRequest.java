package com.marketingai.dto.budget;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateGuardRequest {

    /** PER_CREATIVE_STOP_LOSS or MONTHLY_CEILING */
    @NotBlank
    private String guardType;

    /** creative_id for PER_CREATIVE_STOP_LOSS; null for MONTHLY_CEILING */
    private UUID referenceId;

    @Positive
    private float threshold;
}
