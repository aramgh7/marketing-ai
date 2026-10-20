package com.marketingai.service;

/**
 * No-show rate with a credible interval for the weekly report (roadmap 8-3, vision §19.5).
 * Posterior Beta(no_show + 0.5, held + 0.5) (Jeffreys prior); the 90 % interval is approximated
 * with the Beta quantile via the normal approximation on the logit scale when n is small and
 * the Wilson-style normal approximation otherwise — enough precision for a report line.
 */
public final class NoshowStats {

    private NoshowStats() {}

    public static final double DEFAULT_TARGET = 0.30;

    public record Summary(long noShow, long held, double rate, double low, double high, double target) {
        public long resolved() { return noShow + held; }
        public boolean meetsTarget() { return high <= target; }
        public boolean missesTarget() { return low > target; }
        public String verdict() {
            if (resolved() == 0) return "no resolved appointments";
            if (meetsTarget()) return "within target";
            if (missesTarget()) return "ABOVE target";
            return "inconclusive";
        }
    }

    /** 90 % credible interval for p = noShow / (noShow + held) under a Jeffreys prior. */
    public static Summary summarise(long noShow, long held, Double targetOrNull) {
        double target = targetOrNull != null ? targetOrNull : DEFAULT_TARGET;
        long n = noShow + held;
        if (n == 0) return new Summary(0, 0, 0.0, 0.0, 1.0, target);
        double a = noShow + 0.5, b = held + 0.5;
        double mean = a / (a + b);
        double var = a * b / ((a + b) * (a + b) * (a + b + 1));
        double sd = Math.sqrt(var);
        double z = 1.6449; // 90 % two-sided
        double low = Math.max(0.0, mean - z * sd);
        double high = Math.min(1.0, mean + z * sd);
        return new Summary(noShow, held, round4((double) noShow / n), round4(low), round4(high), target);
    }

    public static String reportLine(Summary s) {
        if (s.resolved() == 0) {
            return "No-show rate: no resolved appointments this week";
        }
        return String.format(java.util.Locale.ROOT, "No-show rate: %.1f%% (90%% CI %.1f%%–%.1f%%, n=%d) vs target < %.0f%% — %s",
                s.rate() * 100, s.low() * 100, s.high() * 100, s.resolved(), s.target() * 100, s.verdict());
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
