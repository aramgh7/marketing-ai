package com.marketingai.service;

/**
 * Pure logic for the 15-minute slot-capacity refresh (roadmap 9-8; arch §16.15):
 * expected no-shows per team slot = booked × p_noshow; overbook = floor(expected), capped at 2
 * (overbooking beyond two risks two investors showing up for one coordinator).
 */
public final class SlotCapacityMath {

    private SlotCapacityMath() {}

    public static final int MIN_RESOLVED = 30;
    public static final int MAX_OVERBOOK = 2;

    public record NoShowMix(long completed, long noShow) {
        public long resolved() { return completed + noShow; }
        public double pNoShow() { return resolved() == 0 ? 0.0 : (double) noShow / resolved(); }
    }

    public static boolean enoughData(NoShowMix mix) {
        return mix.resolved() >= MIN_RESOLVED;
    }

    /** @param avgBookedPerSlot mean appointments booked into one team slot (hour) over the window */
    public static int overbook(double pNoShow, double avgBookedPerSlot) {
        return overbook(pNoShow, avgBookedPerSlot, MAX_OVERBOOK);
    }

    public static int overbook(double pNoShow, double avgBookedPerSlot, int cap) {
        if (pNoShow <= 0 || avgBookedPerSlot <= 0) return 0;
        double expectedNoShows = avgBookedPerSlot * pNoShow;
        return (int) Math.min(cap, Math.floor(expectedNoShows));
    }
}
