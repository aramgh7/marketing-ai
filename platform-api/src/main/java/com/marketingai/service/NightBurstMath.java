package com.marketingai.service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Night-hour click burst detection in the tenant's timezone (roadmap 3-20; vision §12.6.2).
 * Night = 23:00–05:00 local time; a burst = {@code threshold}+ events inside a sliding window.
 * Pure, unit-tested; the scheduler feeds it event timestamps from the last 24 h.
 */
public final class NightBurstMath {

    private NightBurstMath() {}

    public static final int DEFAULT_THRESHOLD = 10;
    public static final int DEFAULT_WINDOW_MINUTES = 15;

    public record Result(boolean burst, int nightEvents, int maxInWindow, int windowMinutes, int threshold) {}

    /** Tenant timezone from settings; falls back to UTC on anything invalid. */
    public static ZoneId zoneOf(Object tz) {
        if (tz == null || tz.toString().isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(tz.toString().trim());
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    public static boolean isNightHour(int localHour) {
        return localHour >= 23 || localHour < 5;
    }

    public static boolean isNight(Instant t, ZoneId zone) {
        return isNightHour(ZonedDateTime.ofInstant(t, zone).getHour());
    }

    public static Result detect(List<Instant> events, ZoneId zone, int threshold, int windowMinutes) {
        List<Instant> night = new ArrayList<>();
        for (Instant t : events) if (t != null && isNight(t, zone)) night.add(t);
        night.sort(null);
        int max = 0;
        long window = windowMinutes * 60L;
        int j = 0;
        for (int i = 0; i < night.size(); i++) {
            while (j < night.size() && night.get(j).getEpochSecond() - night.get(i).getEpochSecond() <= window) j++;
            max = Math.max(max, j - i);
        }
        return new Result(max >= threshold, night.size(), max, windowMinutes, threshold);
    }
}
