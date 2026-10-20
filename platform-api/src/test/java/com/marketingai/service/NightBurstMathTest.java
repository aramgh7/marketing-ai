package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 3-20: night-hour click bursts in the tenant's local time. */
class NightBurstMathTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void nightIsLocalNotUtc() {
        // 03:30 UTC = 23:30 previous day in New York (EDT) → night; 15:30 UTC = 11:30 NY → day
        assertThat(NightBurstMath.isNight(Instant.parse("2026-10-20T03:30:00Z"), NY)).isTrue();
        assertThat(NightBurstMath.isNight(Instant.parse("2026-10-20T15:30:00Z"), NY)).isFalse();
        assertThat(NightBurstMath.isNight(Instant.parse("2026-10-20T03:30:00Z"), ZoneId.of("UTC"))).isTrue();
    }

    @Test
    void burstWithinWindow() {
        List<Instant> events = new ArrayList<>();
        Instant base = Instant.parse("2026-10-20T04:00:00Z");           // 00:00 NY
        for (int i = 0; i < 12; i++) events.add(base.plusSeconds(60L * i));   // 12 clicks in 12 min
        NightBurstMath.Result r = NightBurstMath.detect(events, NY, 10, 15);
        assertThat(r.burst()).isTrue();
        assertThat(r.maxInWindow()).isEqualTo(12);
        assertThat(r.nightEvents()).isEqualTo(12);
    }

    @Test
    void spreadOutNightClicksAreNotABurst() {
        List<Instant> events = new ArrayList<>();
        Instant base = Instant.parse("2026-10-20T03:00:00Z");
        for (int i = 0; i < 12; i++) events.add(base.plusSeconds(600L * i));  // one every 10 min
        NightBurstMath.Result r = NightBurstMath.detect(events, NY, 10, 15);
        assertThat(r.burst()).isFalse();
        assertThat(r.maxInWindow()).isEqualTo(2);
    }

    @Test
    void daytimeBurstIsIgnored() {
        List<Instant> events = new ArrayList<>();
        Instant base = Instant.parse("2026-10-20T16:00:00Z");           // noon NY
        for (int i = 0; i < 30; i++) events.add(base.plusSeconds(10L * i));
        assertThat(NightBurstMath.detect(events, NY, 10, 15).burst()).isFalse();
    }

    @Test
    void invalidZoneFallsBackToUtc() {
        assertThat(NightBurstMath.zoneOf("Mars/Olympus")).isEqualTo(ZoneId.of("UTC"));
        assertThat(NightBurstMath.zoneOf(null)).isEqualTo(ZoneId.of("UTC"));
        assertThat(NightBurstMath.zoneOf("Europe/Berlin")).isEqualTo(ZoneId.of("Europe/Berlin"));
    }
}
