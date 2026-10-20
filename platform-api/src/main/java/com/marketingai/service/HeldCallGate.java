package com.marketingai.service;

import java.util.Optional;

/**
 * Gate arithmetic on held calls (roadmap 8-3; vision §19.5; arch §16.6) — pure, unit-tested.
 *
 * For an investor funnel the optimisation target is a call that is <em>held</em>, not a booking:
 * <ul>
 *   <li>Gate 2 additionally compares the ad set's booking rate (bookings / clicks) with the group.</li>
 *   <li>Gate 3 compares cost per CALL_HELD with the anchor and kills when
 *       P(show_rate &lt; anchor show_rate) &gt; 0.9 (bookings that do not show are the client's main loss).</li>
 * </ul>
 * Every probability is a Beta(successes+1, failures+1) Monte Carlo comparison via
 * {@link GateEvaluationService#probBetter(double, double, double, double)}.
 */
public final class HeldCallGate {

    private HeldCallGate() {}

    public static final double KILL_THRESHOLD = 0.10;        // P(better) < 10 % → kill (same as the other gates)
    public static final double SHOW_RATE_KILL = 0.90;        // P(show_rate < anchor) > 90 % → kill
    public static final int MIN_BOOKINGS_FOR_SHOW_RATE = 20; // Beta posterior too wide below this
    public static final int MIN_HELD_FOR_COST = 10;          // cost per held call meaningless below this

    /** Observed funnel counts for one ad set (or the anchor / the peer group). */
    public record Counts(long clicks, long bookings, long held, double spend) {
        public double costPerHeld() {
            return held > 0 ? spend / held : Double.POSITIVE_INFINITY;
        }
    }

    /** Gate 2 addition: P(booking_rate &gt; group booking_rate). Empty = not enough data to judge. */
    public static Optional<Double> probBookingRateBetter(Counts adSet, Counts group) {
        if (adSet.clicks() < 50 || group.clicks() < 50) return Optional.empty();
        double a1 = adSet.bookings() + 1.0, b1 = Math.max(adSet.clicks() - adSet.bookings(), 0) + 1.0;
        double a2 = group.bookings() + 1.0, b2 = Math.max(group.clicks() - group.bookings(), 0) + 1.0;
        return Optional.of(GateEvaluationService.probBetter(a1, b1, a2, b2));
    }

    /**
     * Gate 3 on held calls. Returns the kill reason, or empty when the ad set survives / lacks data.
     *
     * @param adSet   counts of the evaluated ad set
     * @param anchor  counts of the anchor ad set (or the group when there is no anchor)
     */
    public static Optional<String> evaluateGate3(Counts adSet, Counts anchor) {
        // 1. Show rate: held / bookings vs anchor
        if (adSet.bookings() >= MIN_BOOKINGS_FOR_SHOW_RATE && anchor.bookings() >= MIN_BOOKINGS_FOR_SHOW_RATE) {
            double a1 = adSet.held() + 1.0,  b1 = Math.max(adSet.bookings() - adSet.held(), 0) + 1.0;
            double a2 = anchor.held() + 1.0, b2 = Math.max(anchor.bookings() - anchor.held(), 0) + 1.0;
            double pLower = 1.0 - GateEvaluationService.probBetter(a1, b1, a2, b2);
            if (pLower > SHOW_RATE_KILL) {
                return Optional.of(String.format(
                        "Gate 3: P(show_rate < anchor) = %.1f%% > 90%% (held %d of %d bookings vs anchor %d of %d)",
                        pLower * 100, adSet.held(), adSet.bookings(), anchor.held(), anchor.bookings()));
            }
        }
        // 2. Cost per held call vs anchor — held rate per click decides, spend only reported
        if (adSet.held() >= MIN_HELD_FOR_COST && anchor.held() >= MIN_HELD_FOR_COST
                && adSet.clicks() > 0 && anchor.clicks() > 0) {
            double a1 = adSet.held() + 1.0,  b1 = Math.max(adSet.clicks() - adSet.held(), 0) + 1.0;
            double a2 = anchor.held() + 1.0, b2 = Math.max(anchor.clicks() - anchor.held(), 0) + 1.0;
            double pBetter = GateEvaluationService.probBetter(a1, b1, a2, b2);
            if (pBetter < KILL_THRESHOLD && adSet.costPerHeld() > anchor.costPerHeld()) {
                return Optional.of(String.format(
                        "Gate 3: cost per CALL_HELD $%.0f vs anchor $%.0f, P(held_rate > anchor) = %.1f%% < 10%%",
                        adSet.costPerHeld(), anchor.costPerHeld(), pBetter * 100));
            }
        }
        return Optional.empty();
    }

    /** True when the gate has enough held-call data to make a decision at all. */
    public static boolean hasEnoughData(Counts adSet) {
        return adSet.bookings() >= MIN_BOOKINGS_FOR_SHOW_RATE || adSet.held() >= MIN_HELD_FOR_COST;
    }
}
