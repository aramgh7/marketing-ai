package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-6: partition naming, bounds and the ensure call. */
class EventPartitionMathTest {

    @Test
    void partition_name_and_bounds() {
        EventPartitionMath.Partition p = EventPartitionMath.forMonth(YearMonth.of(2027, 4));
        assertThat(p.name()).isEqualTo("events_2027_04");
        assertThat(p.from()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(p.to()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(EventPartitionMath.isValidName(p.name())).isTrue();
        assertThat(EventPartitionMath.isValidName("events; DROP TABLE x")).isFalse();
    }

    @Test
    void required_covers_this_month_plus_two_and_crosses_the_year() {
        List<EventPartitionMath.Partition> req = EventPartitionMath.required(LocalDate.of(2027, 11, 15));
        assertThat(req).extracting(EventPartitionMath.Partition::name)
                .containsExactly("events_2027_11", "events_2027_12", "events_2028_01");
    }

    @Test
    void ensure_call_binds_name_and_bounds() {
        Object[] args = EventPartitionMath.ensureArgs(EventPartitionMath.forMonth(YearMonth.of(2027, 4)));
        assertThat(EventPartitionMath.ENSURE_SQL).isEqualTo("SELECT public.ensure_events_partition(?, ?, ?)");
        assertThat(args[0]).isEqualTo("events_2027_04");
        assertThat(args[1]).isEqualTo(java.sql.Date.valueOf("2027-04-01"));
        assertThat(args[2]).isEqualTo(java.sql.Date.valueOf("2027-05-01"));
    }
}
