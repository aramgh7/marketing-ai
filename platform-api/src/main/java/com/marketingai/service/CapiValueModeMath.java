package com.marketingai.service;

import java.util.List;

/**
 * Pure logic for capi_value_mode (roadmap 9-9; arch §15.19 "proxy-value A/B").
 *   PROXY – value = base × k_accr × k_ticket (current behaviour)
 *   FIXED – value = base with every multiplier = 1 (what Meta would get without our enrichment)
 *   AB    – ad sets alternate PROXY / FIXED; the weekly comparison uses cost per CALL_HELD
 */
public final class CapiValueModeMath {

    public static final List<String> MODES = List.of("PROXY", "FIXED", "AB");
    public static final int MIN_HELD_PER_ARM = 10;
    public static final double TIE_BAND = 0.05;   // ±5 % cost difference counts as a tie

    public record Comparison(Double proxyCostPerHeld, Double fixedCostPerHeld, String winner, Double liftPct) {}

    private CapiValueModeMath() {}

    public static String resolveMode(String setting) {
        if (setting != null && MODES.contains(setting.toUpperCase())) return setting.toUpperCase();
        return "PROXY";
    }

    /** Mode actually applied to one event: tenant mode wins unless it is AB, then the ad set decides. */
    public static String effectiveMode(String tenantMode, String adSetMode) {
        String t = resolveMode(tenantMode);
        if (!"AB".equals(t)) return t;
        return adSetMode != null && MODES.contains(adSetMode.toUpperCase()) && !"AB".equals(adSetMode.toUpperCase())
                ? adSetMode.toUpperCase() : "PROXY";
    }

    /** Alternate assignment for AB: the n-th assigned ad set (0-based). */
    public static String alternate(long assignedSoFar) {
        return assignedSoFar % 2 == 0 ? "PROXY" : "FIXED";
    }

    /** Cost per held call per arm; winner = lower cost outside the tie band; null costs when no held calls. */
    public static Comparison compare(double proxySpend, int proxyHeld, double fixedSpend, int fixedHeld) {
        Double pc = proxyHeld > 0 ? proxySpend / proxyHeld : null;
        Double fc = fixedHeld > 0 ? fixedSpend / fixedHeld : null;
        if (proxyHeld < MIN_HELD_PER_ARM || fixedHeld < MIN_HELD_PER_ARM || pc == null || fc == null) {
            return new Comparison(pc, fc, "INSUFFICIENT_DATA", null);
        }
        double lift = (fc - pc) / fc * 100.0;   // positive = PROXY cheaper
        String winner = Math.abs(fc - pc) / fc <= TIE_BAND ? "TIE" : (pc < fc ? "PROXY" : "FIXED");
        return new Comparison(pc, fc, winner, lift);
    }
}
