package com.marketingai.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for the CVR-collapse detector (roadmap 9-8; arch §9 / §15.3).
 * Spring only shapes the data; the verdict comes from ml-service {@code POST /signals/cvr-collapse}.
 */
public final class CvrCollapseMath {

    private CvrCollapseMath() {}

    public record HourRow(Instant hour, long sessions, long conversions) {}

    /**
     * Dense 24-slot hourly series ending at {@code now} (truncated to the hour, inclusive):
     * hours without rows become zeros so the detector sees "traffic stopped" as such.
     */
    public static List<Map<String, Object>> denseSeries(List<HourRow> rows, Instant now) {
        Instant end = now.truncatedTo(ChronoUnit.HOURS);
        Map<Instant, HourRow> byHour = new LinkedHashMap<>();
        for (HourRow r : rows) byHour.put(r.hour().truncatedTo(ChronoUnit.HOURS), r);
        List<Map<String, Object>> out = new ArrayList<>(24);
        for (int i = 23; i >= 0; i--) {
            Instant h = end.minus(i, ChronoUnit.HOURS);
            HourRow r = byHour.get(h);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", h.toString());
            m.put("sessions", r == null ? 0L : r.sessions());
            m.put("conversions", r == null ? 0L : r.conversions());
            out.add(m);
        }
        return out;
    }

    /** An entity is worth sending when it had enough traffic in the window to say anything. */
    public static boolean worthChecking(List<Map<String, Object>> series, long minSessions24h) {
        long total = 0;
        for (Map<String, Object> m : series) total += ((Number) m.get("sessions")).longValue();
        return total >= minSessions24h;
    }
}
