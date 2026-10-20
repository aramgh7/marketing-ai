package com.marketingai.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure metric shaping for the daily Message-Market Fit scan (roadmap 9-8; arch §9 / §15.6).
 * Builds the body ml-service {@code POST /mmf/diagnose} expects from campaign aggregates.
 */
public final class MmfScanMath {

    private MmfScanMath() {}

    public static final long MIN_IMPRESSIONS = 1_000;

    public static boolean enoughData(long impressions, long clicks) {
        return impressions >= MIN_IMPRESSIONS && clicks >= 30;
    }

    /**
     * @param roasHint   realised ROAS when the tenant records revenue; null → neutral 2.0 (the diagnosis
     *                   then rests on CTR/CVR/CPC, which is what "offer problem" is about)
     */
    public static Map<String, Object> metrics(long impressions, long clicks, long conversions,
                                              double spendTotal, double spendToday, Double dailyBudget,
                                              String learningPhase, Double roasHint) {
        double ctr = impressions > 0 ? (double) clicks / impressions : 0.0;
        double cvr = clicks > 0 ? (double) conversions / clicks : 0.0;
        double cpc = clicks > 0 ? spendTotal / clicks : 0.0;
        double budgetUtil = dailyBudget != null && dailyBudget > 0 ? Math.min(1.0, spendToday / dailyBudget) : 0.5;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ctr", round(ctr));
        m.put("cvr", round(cvr));
        m.put("cpc", round(cpc));
        m.put("roas", roasHint != null ? round(roasHint) : 2.0);
        m.put("impression_share", 0.3);
        m.put("budget_util", round(budgetUtil));
        m.put("learning_phase", learningPhase == null ? "" : learningPhase);
        m.put("frequency", 1.0);
        m.put("ctr_benchmark", 0.005);
        return m;
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
