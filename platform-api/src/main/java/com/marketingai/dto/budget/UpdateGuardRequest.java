package com.marketingai.dto.budget;

import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class UpdateGuardRequest {

    @Positive
    private Float threshold;

    /** Set to true to manually reset a triggered guard after reviewing. */
    private Boolean resetTrigger;
}
