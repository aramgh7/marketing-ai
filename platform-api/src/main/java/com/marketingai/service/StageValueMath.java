package com.marketingai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic for the weekly stage-value refresh (roadmap 9-8; arch §16.4 "expected_value_usd per
 * stage from closed cohorts"): E[funds | session reached stage s] = Σ closed-deal revenue / #sessions
 * that reached s. Replaces the §19.2 seed values once the tenant has ≥ 20 closed deals.
 */
public final class StageValueMath {

    private StageValueMath() {}

    public static final int MIN_CLOSED_DEALS = 20;

    public record StageValue(double expectedValueUsd, long nReached, long nClosed) {}

    /**
     * @param reachedByStage stage_key → distinct sessions that reached the stage in the cohort window
     * @param closedRevenue  revenue of each closed deal in the same window
     * @return stage_key → value; empty when the cohort is too small
     */
    public static Map<String, StageValue> expectedValues(Map<String, Long> reachedByStage,
                                                         List<Double> closedRevenue) {
        Map<String, StageValue> out = new LinkedHashMap<>();
        long nClosed = closedRevenue.size();
        if (nClosed < MIN_CLOSED_DEALS) return out;
        double total = 0;
        for (Double r : closedRevenue) if (r != null && r > 0) total += r;
        for (Map.Entry<String, Long> e : reachedByStage.entrySet()) {
            long n = e.getValue() == null ? 0 : e.getValue();
            if (n <= 0) continue;
            // a stage reached by fewer sessions than closed deals is an upstream data gap — cap at n = nClosed
            long denom = Math.max(n, nClosed);
            out.put(e.getKey(), new StageValue(Math.round(total / denom * 100) / 100.0, n, nClosed));
        }
        return out;
    }
}
