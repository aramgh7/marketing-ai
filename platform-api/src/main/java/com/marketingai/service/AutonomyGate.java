package com.marketingai.service;

import java.util.List;

/**
 * Pure decision table for autonomy-level changes (roadmap 9-3; arch §15.11 / §15.13; vision §12.4–12.5):
 * a tenant may move above ADVISOR only when the latest off-policy evaluation is PASS and not older
 * than {@link #MAX_OPE_AGE_DAYS}. Downgrades are always allowed.
 */
public final class AutonomyGate {

    public static final List<String> LEVELS = List.of("OBSERVER", "ADVISOR", "SEMI_AUTOPILOT", "AUTOPILOT");
    public static final int MAX_OPE_AGE_DAYS = 7;

    public record Decision(boolean allowed, String reason) {}

    private AutonomyGate() {}

    public static int rank(String level) {
        int i = LEVELS.indexOf(level == null ? "" : level.toUpperCase());
        return i < 0 ? LEVELS.indexOf("ADVISOR") : i;
    }

    public static boolean isValid(String level) {
        return level != null && LEVELS.contains(level.toUpperCase());
    }

    /** True when the level lets the platform write to Meta without a human click. */
    public static boolean isAutonomous(String level) {
        return rank(level) > rank("ADVISOR");
    }

    /**
     * @param opeVerdict  latest ope_runs.verdict (PASS / FAIL / INSUFFICIENT_DATA) or null when no run exists
     * @param opeAgeDays  age of that run in days (ignored when verdict is null)
     */
    public static Decision evaluate(String current, String target, String opeVerdict, Long opeAgeDays) {
        if (!isValid(target)) {
            return new Decision(false, "Invalid autonomy level '" + target + "'. Valid: " + LEVELS);
        }
        String t = target.toUpperCase();
        if (rank(t) <= rank(current)) {
            return new Decision(true, "downgrade or no change — always allowed");
        }
        if (!isAutonomous(t)) {
            return new Decision(true, "ADVISOR and below need no OPE gate");
        }
        if (opeVerdict == null) {
            return new Decision(false, "OPE gate: no off-policy evaluation run yet — run POST /ope/evaluate first");
        }
        if (!"PASS".equalsIgnoreCase(opeVerdict)) {
            return new Decision(false, "OPE gate: latest verdict is " + opeVerdict + " (PASS required)");
        }
        if (opeAgeDays == null || opeAgeDays > MAX_OPE_AGE_DAYS) {
            return new Decision(false, "OPE gate: latest PASS is " + opeAgeDays + " days old (max " + MAX_OPE_AGE_DAYS + ")");
        }
        return new Decision(true, "OPE PASS " + opeAgeDays + " days old");
    }

    /** Level to fall back to when a scheduled OPE fails while the tenant runs autonomously. */
    public static String levelAfterFailedOpe(String current) {
        return isAutonomous(current) ? "ADVISOR" : current;
    }
}
