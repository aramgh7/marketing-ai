package com.marketingai.dto.meta;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Creates multiple ad sets in a single TEST campaign in one shot.
 * All ad sets receive equal budgets (vision §6.5: equal budgets for clean comparison).
 */
@Getter
@Setter
public class TestBatchRequest {

    @NotNull
    private java.util.UUID campaignId;

    /** Equal daily budget for every ad set in this batch (in account currency) */
    @NotNull
    private Float perAdSetDailyBudget;

    @Valid
    @NotEmpty
    @Size(min = 2, max = 11)
    private List<AdSetRequest> adSets;

    /**
     * Creative IDs that should be flagged as exploration slots.
     * Used when the batch was built from ExplorationBudgetService.recommendBatch().
     * Optional — if absent, no exploration flags are set.
     */
    private java.util.Set<java.util.UUID> explorationCreativeIds;

    /**
     * If the batch has fewer than 2 anchor ad sets, this reason is required to proceed.
     * The override is logged in anchor_override_log (vision §11.2).
     */
    private String anchorOverrideReason;
}
