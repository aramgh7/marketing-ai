package com.marketingai.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Delta arithmetic for Meta insight snapshots (audit 6.3).
 *
 * Meta's {@code date_preset=today} returns a cumulative day-to-date figure. Adding it to a running
 * total on every 30-minute sync inflated totals ~48x per day. The total must advance by the
 * difference against the previous snapshot taken on the same day; on the first sync of a new day
 * the whole day-to-date value is new.
 *
 * Days are compared in UTC; Meta's "today" follows the ad-account timezone, so around midnight a
 * sync may attribute up to one interval to the wrong day — bounded, and never cumulative.
 */
public final class MetaSyncMath {

    private MetaSyncMath() {}

    static boolean sameUtcDay(Instant a, Instant b) {
        if (a == null || b == null) return false;
        return LocalDate.ofInstant(a, ZoneOffset.UTC).equals(LocalDate.ofInstant(b, ZoneOffset.UTC));
    }

    /** Spend to add to the running total given the new day-to-date value. Never negative. */
    public static float spendDelta(float currentToday, Float prevToday, Instant prevSyncedAt, Instant now) {
        if (!sameUtcDay(prevSyncedAt, now) || prevToday == null) return Math.max(0f, currentToday);
        return Math.max(0f, currentToday - prevToday);
    }

    /** Same for integer counters (impressions, clicks, conversions). */
    public static long countDelta(long currentToday, Long prevToday, Instant prevSyncedAt, Instant now) {
        if (!sameUtcDay(prevSyncedAt, now) || prevToday == null) return Math.max(0L, currentToday);
        return Math.max(0L, currentToday - prevToday);
    }
}
