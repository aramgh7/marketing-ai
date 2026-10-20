package com.marketingai.service;

/**
 * Pure fallback estimate for the weekly Reg CF waitlist valuation (roadmap 9-8; arch §16.20).
 * ml-service {@code POST /waitlist/value} is the primary path; this mirrors its point estimate and a
 * Wilson interval on the conversion rate so the weekly row is written even when ML is down.
 * ⚠️ Valuation only — nothing here touches Meta audiences (OI-3 freeze).
 */
public final class RegCfWaitlistMath {

    private RegCfWaitlistMath() {}

    public record Estimate(int nLeads, double pConvert, double avgTicket, double value, double ciLower, double ciUpper) {}

    public static Estimate estimate(int nLeads, double pConvert, double avgTicket) {
        double p = Math.min(Math.max(pConvert, 0.0), 1.0);
        double value = nLeads * p * avgTicket;
        // Wilson 90% interval on p with an assumed n = max(nLeads, 1) observations
        double n = Math.max(nLeads, 1);
        double z = 1.6449;
        double denom = 1 + z * z / n;
        double centre = (p + z * z / (2 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
        double lo = Math.max(0.0, centre - half);
        double hi = Math.min(1.0, centre + half);
        return new Estimate(nLeads, p, avgTicket, round(value), round(lo * nLeads * avgTicket), round(hi * nLeads * avgTicket));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
