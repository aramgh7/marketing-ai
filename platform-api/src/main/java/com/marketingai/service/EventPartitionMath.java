package com.marketingai.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Pure helpers for monthly {@code events} partitions (roadmap 9-6; arch §9 "Event partition create").
 * V5 pre-created partitions up to 2027-03; from then on {@code EventPartitionScheduler} asks the
 * SECURITY DEFINER function {@code ensure_events_partition} (V128) to create the partitions for the
 * current month + {@link #MONTHS_AHEAD} — the app role does not own the parent table, the function does.
 */
public final class EventPartitionMath {

    public static final int MONTHS_AHEAD = 2;
    public static final String ENSURE_SQL = "SELECT public.ensure_events_partition(?, ?, ?)";

    public record Partition(String name, LocalDate from, LocalDate to) {}

    private EventPartitionMath() {}

    public static Partition forMonth(YearMonth ym) {
        return new Partition(
                "events_%04d_%02d".formatted(ym.getYear(), ym.getMonthValue()),
                ym.atDay(1), ym.plusMonths(1).atDay(1));
    }

    /** Partitions that must exist as of {@code today}: this month and the next MONTHS_AHEAD months. */
    public static List<Partition> required(LocalDate today) {
        YearMonth start = YearMonth.from(today);
        return java.util.stream.IntStream.rangeClosed(0, MONTHS_AHEAD)
                .mapToObj(i -> forMonth(start.plusMonths(i)))
                .toList();
    }

    /** Bind values for {@link #ENSURE_SQL}. */
    public static Object[] ensureArgs(Partition p) {
        return new Object[]{p.name(), java.sql.Date.valueOf(p.from()), java.sql.Date.valueOf(p.to())};
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches("^events_[0-9]{4}_[0-9]{2}$");
    }
}
