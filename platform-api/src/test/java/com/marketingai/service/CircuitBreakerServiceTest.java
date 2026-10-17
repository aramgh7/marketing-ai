package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for CircuitBreaker Bayesian math and erf approximation.
 * No Spring context — pure static method tests.
 */
class CircuitBreakerServiceTest {

    // ─── erf ────────────────────────────────────────────────────────────────

    @Test
    void erf_zero_returnsZero() {
        assertThat(CircuitBreakerService.erf(0.0)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void erf_knownValue_onePointZero() {
        // erf(1.0) ≈ 0.8427008
        assertThat(CircuitBreakerService.erf(1.0)).isCloseTo(0.8427, within(0.001));
    }

    @Test
    void erf_largePositive_saturatesAtOne() {
        assertThat(CircuitBreakerService.erf(5.0)).isCloseTo(1.0, within(1e-4));
    }

    @Test
    void erf_largeNegative_saturatesAtMinusOne() {
        assertThat(CircuitBreakerService.erf(-5.0)).isCloseTo(-1.0, within(1e-4));
    }

    @Test
    void erf_isOddFunction() {
        // erf(-x) = -erf(x)
        for (double x : new double[]{0.5, 1.0, 2.0, 3.0}) {
            assertThat(CircuitBreakerService.erf(-x))
                    .isCloseTo(-CircuitBreakerService.erf(x), within(1e-9));
        }
    }

    // ─── bayesianProbCpaTooHigh ──────────────────────────────────────────────

    @Test
    void probCpaTooHigh_zeroCon_highSpend_returnsHighProb() {
        // spend=1000, conversions=0, target_cpa=10 → CPA=∞ >> 10 → P should be high
        double p = CircuitBreakerService.bayesianProbCpaTooHigh(1000f, 0f, 10f);
        assertThat(p).isGreaterThan(0.80);
    }

    @Test
    void probCpaTooHigh_highCPA_returnsHighProb() {
        // spend=500, conversions=1, target_cpa=10 → CPA≈500 >> 10
        double p = CircuitBreakerService.bayesianProbCpaTooHigh(500f, 1f, 10f);
        assertThat(p).isGreaterThan(0.75);
    }

    @Test
    void probCpaTooHigh_goodCPA_returnsZero() {
        // spend=50, conversions=100, target_cpa=10 → CPA=0.5 << 10 → P=0
        double p = CircuitBreakerService.bayesianProbCpaTooHigh(50f, 100f, 10f);
        assertThat(p).isEqualTo(0.0);
    }

    @Test
    void probCpaTooHigh_alwaysInUnitInterval() {
        float[] spends = {1, 10, 100, 1000};
        float[] convs  = {0, 1, 5, 50};
        for (float s : spends) {
            for (float c : convs) {
                double p = CircuitBreakerService.bayesianProbCpaTooHigh(s, c, 10f);
                assertThat(p).isBetween(0.0, 1.0);
            }
        }
    }

    // ─── bayesianProbRoasBelowBreakeven ──────────────────────────────────────

    @Test
    void probRoasBelowBreakeven_zeroConversions_highSpend_returnsHighProb() {
        double p = CircuitBreakerService.bayesianProbRoasBelowBreakeven(1000f, 0f, 1.0f);
        assertThat(p).isGreaterThan(0.80);
    }

    @Test
    void probRoasBelowBreakeven_goodRoas_returnsZero() {
        // spend=10, conversions=10000, breakeven=0.1 → clearly profitable
        double p = CircuitBreakerService.bayesianProbRoasBelowBreakeven(10f, 10_000f, 0.1f);
        assertThat(p).isEqualTo(0.0);
    }

    @Test
    void probRoasBelowBreakeven_alwaysInUnitInterval() {
        float[] spends = {1, 100, 1000};
        float[] convs  = {0, 1, 10};
        for (float s : spends) {
            for (float c : convs) {
                double p = CircuitBreakerService.bayesianProbRoasBelowBreakeven(s, c, 1.0f);
                assertThat(p).isBetween(0.0, 1.0);
            }
        }
    }
}
