package com.marketingai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic for the weekly self-report calibration (roadmap 9-8; arch §16.4):
 * P(ACCRED_VERIFIED | self_accredited = yes) per creative × ticket bucket. A creative whose
 * self-reported "accredited" leads rarely verify gets a smaller k_accr so Lead values sent to Meta
 * stop rewarding it.
 */
public final class SelfReportCalibrationMath {

    private SelfReportCalibrationMath() {}

    public static final int MIN_SESSIONS = 20;
    public static final double TRUST_RATE = 0.5;     // at or above → full k_accr
    public static final double FLOOR = 0.2;

    public record Cell(String creativeId, String ticketBucket, long n, long verified) {
        public double rate() { return n == 0 ? 0.0 : (double) verified / n; }
    }

    /** k_accr multiplier for a verification rate: 1.0 when trusted, else max(0.2, rate / 0.8). */
    public static double kAccr(double rate) {
        if (rate >= TRUST_RATE) return 1.0;
        return Math.max(FLOOR, rate / 0.8);
    }

    /**
     * Cells below {@link #MIN_SESSIONS} are skipped. Output key {@code "<creativeId>|<bucket>"} →
     * {rate, n, verified, k_accr}; stored as tenants.value_coeffs.k_accr_by_creative.
     */
    public static Map<String, Map<String, Object>> build(List<Cell> cells) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Cell c : cells) {
            if (c.n() < MIN_SESSIONS || c.creativeId() == null) continue;
            String bucket = c.ticketBucket() == null ? "ALL" : c.ticketBucket();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("rate", Math.round(c.rate() * 10_000) / 10_000.0);
            v.put("n", c.n());
            v.put("verified", c.verified());
            v.put("k_accr", Math.round(kAccr(c.rate()) * 10_000) / 10_000.0);
            out.put(c.creativeId() + "|" + bucket, v);
        }
        return out;
    }
}
