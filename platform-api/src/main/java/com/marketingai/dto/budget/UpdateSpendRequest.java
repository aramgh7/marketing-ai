package com.marketingai.dto.budget;

import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class UpdateSpendRequest {

    /** Current spend pulled manually from Meta Ads Manager (USD). */
    @PositiveOrZero
    private float currentSpend;
}
