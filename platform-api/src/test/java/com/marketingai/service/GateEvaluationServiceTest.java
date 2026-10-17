package com.marketingai.service;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Bayesian gate math (probBetter, samplers).
 * No Spring context — pure static method tests.
 */
class GateEvaluationServiceTest {

    // ─── probBetter ─────────────────────────────────────────────────────────

    @Test
    void probBetter_equalPriors_nearHalf() {
        double p = GateEvaluationService.probBetter(1, 1, 1, 1);
        assertThat(p).isBetween(0.42, 0.58);
    }

    @Test
    void probBetter_strongArmVsWeak_nearOne() {
        // Beta(1000, 1) vs Beta(1, 1000) → arm 1 almost certainly wins
        double p = GateEvaluationService.probBetter(1000, 1, 1, 1000);
        assertThat(p).isGreaterThan(0.99);
    }

    @Test
    void probBetter_weakVsStrong_nearZero() {
        double p = GateEvaluationService.probBetter(1, 1000, 1000, 1);
        assertThat(p).isLessThan(0.01);
    }

    @Test
    void probBetter_moderateAdvantage_returnsReasonableRange() {
        // Beta(50,50) mean=0.50 vs Beta(30,70) mean=0.30 — arm 1 clearly dominates
        // Theoretical P≈0.998, so assert > 0.95 with MC noise headroom
        double p = GateEvaluationService.probBetter(50, 50, 30, 70);
        assertThat(p).isGreaterThan(0.95);
    }

    @Test
    void probBetter_symmetric_nearHalf() {
        // Same parameters → ~50/50
        double p = GateEvaluationService.probBetter(10, 20, 10, 20);
        assertThat(p).isBetween(0.40, 0.60);
    }

    // ─── sampleBeta ─────────────────────────────────────────────────────────

    @Test
    void sampleBeta_alwaysInUnitInterval() {
        Random rng = new Random(42);
        for (int i = 0; i < 2000; i++) {
            double s = GateEvaluationService.sampleBeta(2.0, 3.0, rng);
            assertThat(s).isBetween(0.0, 1.0);
        }
    }

    @Test
    void sampleBeta_concentratedAroundExpectedMean() {
        // Beta(10, 10): E[X] = 0.5
        Random rng = new Random(0);
        double sum = 0;
        int n = 5_000;
        for (int i = 0; i < n; i++) sum += GateEvaluationService.sampleBeta(10.0, 10.0, rng);
        assertThat(sum / n).isBetween(0.46, 0.54);
    }

    @Test
    void sampleBeta_highAlpha_concentratesNearOne() {
        // Beta(100, 1): E[X] ≈ 0.99
        Random rng = new Random(1);
        double sum = 0;
        int n = 2_000;
        for (int i = 0; i < n; i++) sum += GateEvaluationService.sampleBeta(100.0, 1.0, rng);
        assertThat(sum / n).isGreaterThan(0.95);
    }

    @Test
    void sampleBeta_smallAlpha_worksWithFractionalShape() {
        // shape < 1 uses the reduction rule — must still be in [0,1]
        Random rng = new Random(7);
        for (int i = 0; i < 500; i++) {
            double s = GateEvaluationService.sampleBeta(0.5, 0.5, rng);
            assertThat(s).isBetween(0.0, 1.0);
        }
    }

    // ─── sampleGamma ────────────────────────────────────────────────────────

    @Test
    void sampleGamma_alwaysPositive_smallShape() {
        Random rng = new Random(99);
        for (int i = 0; i < 1000; i++) {
            assertThat(GateEvaluationService.sampleGamma(0.1, rng)).isGreaterThan(0.0);
        }
    }

    @Test
    void sampleGamma_alwaysPositive_largeShape() {
        Random rng = new Random(123);
        for (int i = 0; i < 1000; i++) {
            assertThat(GateEvaluationService.sampleGamma(50.0, rng)).isGreaterThan(0.0);
        }
    }

    @Test
    void sampleGamma_meanMatchesShape() {
        // E[Gamma(shape, 1)] = shape
        Random rng = new Random(55);
        double shape = 5.0;
        double sum = 0;
        int n = 10_000;
        for (int i = 0; i < n; i++) sum += GateEvaluationService.sampleGamma(shape, rng);
        assertThat(sum / n).isBetween(4.7, 5.3);
    }
}
