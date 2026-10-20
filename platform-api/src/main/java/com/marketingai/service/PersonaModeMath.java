package com.marketingai.service;

import java.util.List;
import java.util.Random;

/**
 * Pure logic for the persona-sandbox protocol (roadmap 9-9; arch §15.9).
 *
 * persona_mode ∈ {OFF, LOG_ONLY, AB_TEST, PREFILTER}:
 *   OFF        – no persona scoring
 *   LOG_ONLY   – score and log, never influence the batch
 *   AB_TEST    – alternate batches between PREFILTER (persona-filtered candidates) and CONTROL
 *   PREFILTER  – every batch is persona-filtered (promotion target)
 *
 * Promotion rule: ≥ {@link #MIN_AB_DAYS} days in AB_TEST, ≥ {@link #MIN_BATCHES_PER_ARM} batches
 * per arm, and P(gate pass rate PREFILTER > CONTROL) > {@link #PROMOTE_P} (Beta posteriors, MC).
 */
public final class PersonaModeMath {

    public static final List<String> MODES = List.of("OFF", "LOG_ONLY", "AB_TEST", "PREFILTER");
    public static final int MIN_AB_DAYS = 90;
    public static final int MIN_BATCHES_PER_ARM = 20;
    public static final double PROMOTE_P = 0.9;
    private static final int MC = 20_000;

    public record Decision(boolean promote, double pBetter, String reason) {}

    private PersonaModeMath() {}

    /** Effective mode: an explicit valid setting wins; otherwise AB_TEST when a persona exists, else OFF. */
    public static String resolveMode(String setting, boolean hasPersona) {
        if (setting != null && MODES.contains(setting.toUpperCase())) return setting.toUpperCase();
        return hasPersona ? "AB_TEST" : "OFF";
    }

    /** Arm for the n-th batch (0-based) under AB_TEST: strict alternation keeps the arms balanced. */
    public static String arm(String mode, long batchesSoFar) {
        return switch (mode) {
            case "PREFILTER" -> "PREFILTER";
            case "AB_TEST" -> batchesSoFar % 2 == 0 ? "PREFILTER" : "CONTROL";
            default -> null;
        };
    }

    public static Decision evaluate(int prefilterPassed, int prefilterTotal, int controlPassed, int controlTotal,
                                    long abDays) {
        return evaluate(prefilterPassed, prefilterTotal, controlPassed, controlTotal, abDays, new Random(42));
    }

    public static Decision evaluate(int prefilterPassed, int prefilterTotal, int controlPassed, int controlTotal,
                                    long abDays, Random rng) {
        if (prefilterTotal < MIN_BATCHES_PER_ARM || controlTotal < MIN_BATCHES_PER_ARM) {
            return new Decision(false, Double.NaN, "need ≥ " + MIN_BATCHES_PER_ARM + " batches per arm (prefilter="
                    + prefilterTotal + ", control=" + controlTotal + ")");
        }
        double p = pBetter(prefilterPassed, prefilterTotal, controlPassed, controlTotal, rng);
        if (abDays < MIN_AB_DAYS) {
            return new Decision(false, p, "AB_TEST running " + abDays + " days (< " + MIN_AB_DAYS + ")");
        }
        if (p <= PROMOTE_P) {
            return new Decision(false, p, String.format(java.util.Locale.ROOT,
                    "P(prefilter better) = %.3f ≤ %.2f", p, PROMOTE_P));
        }
        return new Decision(true, p, String.format(java.util.Locale.ROOT,
                "P(prefilter better) = %.3f > %.2f after %d days", p, PROMOTE_P, abDays));
    }

    /** P(rate_A > rate_B) with Beta(1 + s, 1 + f) posteriors, Monte Carlo. */
    static double pBetter(int aS, int aN, int bS, int bN, Random rng) {
        int wins = 0;
        for (int i = 0; i < MC; i++) {
            double a = sampleBeta(1 + aS, 1 + (aN - aS), rng);
            double b = sampleBeta(1 + bS, 1 + (bN - bS), rng);
            if (a > b) wins++;
        }
        return wins / (double) MC;
    }

    /** Beta via two Gamma draws (Marsaglia–Tsang). */
    static double sampleBeta(double alpha, double beta, Random rng) {
        double x = sampleGamma(alpha, rng), y = sampleGamma(beta, rng);
        return x / (x + y);
    }

    static double sampleGamma(double shape, Random rng) {
        if (shape < 1) {
            return sampleGamma(shape + 1, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0, c = 1.0 / Math.sqrt(9 * d);
        while (true) {
            double x = rng.nextGaussian(), v = 1 + c * x;
            if (v <= 0) continue;
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }
}
