package com.marketingai.service;

/**
 * Expert rating → exploration prior (roadmap 9-9; arch §15.13; vision §12.4 "expert prior / veto").
 * The marketer's 1–5 rating shifts the Beta prior a new arm starts from:
 *   1 → Beta(1,3)   2 → Beta(1,2)   3 → Beta(1,1)   4 → Beta(2,1)   5 → Beta(3,1)
 * A veto is Beta(1,5): the arm is still explorable but starts far below the others.
 * Mirrored in ml-service expert_prior_service.py, which also combines it with the GLM prior.
 */
public final class ExpertPriorMath {

    public record Prior(double alpha, double beta) {}

    private ExpertPriorMath() {}

    public static Prior shift(int rating, boolean veto) {
        if (veto) return new Prior(1.0, 5.0);
        return switch (Math.max(1, Math.min(5, rating))) {
            case 1 -> new Prior(1.0, 3.0);
            case 2 -> new Prior(1.0, 2.0);
            case 4 -> new Prior(2.0, 1.0);
            case 5 -> new Prior(3.0, 1.0);
            default -> new Prior(1.0, 1.0);
        };
    }
}
