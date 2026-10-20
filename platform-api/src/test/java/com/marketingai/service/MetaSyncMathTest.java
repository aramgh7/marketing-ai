package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit 6.3: totals must grow by the delta between day-to-date snapshots, not by the snapshot itself. */
class MetaSyncMathTest {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-10-20T10:30:00Z");
    private static final Instant NEXT_DAY = Instant.parse("2026-10-21T00:10:00Z");

    @Test
    void sameDay_addsOnlyTheIncrease() {
        assertThat(MetaSyncMath.spendDelta(120f, 100f, T0, T1)).isEqualTo(20f);
        assertThat(MetaSyncMath.countDelta(1_500L, 1_200L, T0, T1)).isEqualTo(300L);
    }

    @Test
    void sameDay_noChange_addsZero() {
        assertThat(MetaSyncMath.spendDelta(100f, 100f, T0, T1)).isEqualTo(0f);
    }

    @Test
    void newDay_addsWholeDayToDate() {
        assertThat(MetaSyncMath.spendDelta(7.5f, 400f, T1, NEXT_DAY)).isEqualTo(7.5f);
        assertThat(MetaSyncMath.countDelta(40L, 9_000L, T1, NEXT_DAY)).isEqualTo(40L);
    }

    @Test
    void firstSyncEver_addsWholeDayToDate() {
        assertThat(MetaSyncMath.spendDelta(55f, null, null, T1)).isEqualTo(55f);
    }

    @Test
    void metaRestatementDownwards_neverNegative() {
        assertThat(MetaSyncMath.spendDelta(90f, 100f, T0, T1)).isEqualTo(0f);
    }

    @Test
    void halfHourlySyncsOfOneDay_sumToTheDayTotal() {
        Instant midnight = Instant.parse("2026-10-20T00:00:00Z");
        float total = 0f;
        Float prev = null;
        Instant prevAt = null;
        for (int i = 1; i <= 47; i++) {              // 00:30 … 23:30, all on the same UTC day
            Instant at = midnight.plusSeconds(1800L * i);
            float dayToDate = 10f * i;              // Meta reports cumulative spend
            total += MetaSyncMath.spendDelta(dayToDate, prev, prevAt, at);
            prev = dayToDate;
            prevAt = at;
        }
        assertThat(total).isEqualTo(470f);           // was 10+20+...+470 = 11,280 before the fix
    }
}
