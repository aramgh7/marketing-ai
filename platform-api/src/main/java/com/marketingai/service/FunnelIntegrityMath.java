package com.marketingai.service;

/**
 * Daily funnel integrity check arithmetic (roadmap 8-9; vision §19.12):
 * BOOKED (GHL appointments) vs Schedule (Meta CAPI sent) vs FORM_SUBMIT (first-party).
 * The three counters describe the same visitors through three systems; a relative delta above
 * the threshold (default 10 %) means one pipe is leaking (webhook, token, pixel role, mapping).
 */
public final class FunnelIntegrityMath {

    private FunnelIntegrityMath() {}

    public static final double DEFAULT_THRESHOLD = 0.10;
    public static final int MIN_VOLUME = 10;   // below this a delta is noise, not a leak

    public record Result(int formSubmits, int booked, int scheduleSent,
                         Double deltaBookedSchedule, Double deltaFormBooked,
                         String verdict, boolean alert) {}

    /** |a − b| / max(a, b), null when both are zero. */
    public static Double relativeDelta(int a, int b) {
        int denom = Math.max(a, b);
        if (denom == 0) return null;
        return Math.round(Math.abs(a - b) * 10000.0 / denom) / 10000.0;
    }

    public static Result evaluate(int formSubmits, int booked, int scheduleSent, Double thresholdOrNull) {
        double threshold = thresholdOrNull != null ? thresholdOrNull : DEFAULT_THRESHOLD;
        Double dBS = relativeDelta(booked, scheduleSent);
        Double dFB = relativeDelta(formSubmits, booked);
        if (Math.max(Math.max(formSubmits, booked), scheduleSent) < MIN_VOLUME) {
            return new Result(formSubmits, booked, scheduleSent, dBS, dFB, "NO_DATA", false);
        }
        // Form submits legitimately exceed bookings (not everyone books); the leak signal is
        // BOOKED vs Schedule, plus bookings exceeding form submits (bookings we never saw as leads).
        boolean drift = (dBS != null && dBS > threshold) || booked > formSubmits * (1.0 + threshold);
        return new Result(formSubmits, booked, scheduleSent, dBS, dFB, drift ? "DRIFT" : "OK", drift);
    }

    public static String describe(Result r) {
        return String.format(java.util.Locale.ROOT,
                "Funnel integrity (24h): FORM_SUBMIT=%d, BOOKED=%d, Schedule(CAPI)=%d — booked/schedule Δ=%s, form/booked Δ=%s → %s",
                r.formSubmits(), r.booked(), r.scheduleSent(),
                r.deltaBookedSchedule() == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.0f%%", r.deltaBookedSchedule() * 100),
                r.deltaFormBooked() == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.0f%%", r.deltaFormBooked() * 100),
                r.verdict());
    }
}
