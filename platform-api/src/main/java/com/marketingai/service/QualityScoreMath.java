package com.marketingai.service;

/**
 * Session Quality Score (arch §15.5) with the show-rate component from roadmap 8-3 (2-12 change).
 *
 * Base weights: match 0.25, non-bounce 0.25, scroll 0.25, form completion 0.25.
 * When the tenant's quality key is an appointment stage (investor funnel), the session's
 * appointment outcome carries the highest weight: show 0.40, the other four 0.15 each.
 * A session without an appointment outcome yet is scored with the base weights, so the
 * score never penalises people who simply have not had their call.
 */
public final class QualityScoreMath {

    private QualityScoreMath() {}

    public static final double SHOW_WEIGHT = 0.40;

    /**
     * @param matchScore     landing/creative match score, null = neutral 0.5
     * @param bounced        no interaction beyond the page view
     * @param scroll         max scroll depth 0..1
     * @param formCompletion 0 / 0.3 (started) / 1 (submitted)
     * @param showRate       1.0 held, 0.0 no-show, null = no appointment outcome yet
     */
    public static double score(Double matchScore, boolean bounced, double scroll,
                               double formCompletion, Double showRate) {
        double ms = matchScore != null ? clamp(matchScore) : 0.5;
        double nb = bounced ? 0.0 : 1.0;
        double sd = clamp(scroll);
        double fc = clamp(formCompletion);

        double score;
        if (showRate != null) {
            double other = (1.0 - SHOW_WEIGHT) / 4.0;
            score = other * ms + other * nb + other * sd + other * fc + SHOW_WEIGHT * clamp(showRate);
        } else {
            score = 0.25 * ms + 0.25 * nb + 0.25 * sd + 0.25 * fc;
        }
        return Math.round(clamp(score) * 10000.0) / 10000.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
