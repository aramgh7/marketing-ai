package com.marketingai.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for TwoContourService.validateBudgetStep — the ≤20% budget guard
 * that prevents resetting Meta's Learning Phase (vision §6.5).
 * No Spring context needed — tests a public static method.
 */
class TwoContourBudgetGuardTest {

    @Test
    void exactlyTwentyPct_increase_passes() {
        assertThatNoException().isThrownBy(
                () -> TwoContourService.validateBudgetStep(100f, 120f, "entity-1"));
    }

    @Test
    void exactlyTwentyPct_decrease_passes() {
        assertThatNoException().isThrownBy(
                () -> TwoContourService.validateBudgetStep(100f, 80f, "entity-1"));
    }

    @Test
    void twentyOnePct_increase_throws422() {
        assertThatThrownBy(
                () -> TwoContourService.validateBudgetStep(100f, 121f, "entity-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("≤20%");
    }

    @Test
    void twentyOnePct_decrease_throws422() {
        assertThatThrownBy(
                () -> TwoContourService.validateBudgetStep(100f, 79f, "entity-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("≤20%");
    }

    @Test
    void nullCurrentBudget_alwaysPasses() {
        // No previous budget to compare against → validation skipped
        assertThatNoException().isThrownBy(
                () -> TwoContourService.validateBudgetStep(null, 9999f, "entity-1"));
    }

    @Test
    void zeroCurrent_alwaysPasses() {
        assertThatNoException().isThrownBy(
                () -> TwoContourService.validateBudgetStep(0f, 9999f, "entity-1"));
    }

    @Test
    void smallBudget_percentageRuleAppliesCorrectly() {
        // 10 → 12 = 20% increase ✓
        assertThatNoException().isThrownBy(
                () -> TwoContourService.validateBudgetStep(10f, 12f, "entity-1"));

        // 10 → 12.1 = 21% increase ✗
        assertThatThrownBy(
                () -> TwoContourService.validateBudgetStep(10f, 12.1f, "entity-1"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void confirmationBudget_onePointFiveX_typical_throws422() {
        // A typical budget step of 1.5× exceeds 20% — system should guard this
        // (in TwoContourService this is guarded by passing campaign.getDailyBudget())
        assertThatThrownBy(
                () -> TwoContourService.validateBudgetStep(100f, 150f, "campaign-confirm"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
