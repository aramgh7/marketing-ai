package com.marketingai.service;

import com.marketingai.service.HeldCallGate.Counts;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-3: gates on held calls, not bookings. */
class HeldCallGateTest {

    @Test
    void lowShowRateVsAnchorKills() {
        Counts adSet  = new Counts(1_000, 100, 20, 2_000);   // 20 % show up
        Counts anchor = new Counts(1_000, 100, 60, 2_000);   // 60 % show up
        Optional<String> kill = HeldCallGate.evaluateGate3(adSet, anchor);
        assertThat(kill).isPresent();
        assertThat(kill.get()).contains("show_rate");
    }

    @Test
    void comparableShowRateSurvives() {
        Counts adSet  = new Counts(1_000, 100, 55, 2_000);
        Counts anchor = new Counts(1_000, 100, 60, 2_000);
        assertThat(HeldCallGate.evaluateGate3(adSet, anchor)).isEmpty();
    }

    @Test
    void expensiveHeldCallsWithWorseHeldRateKill() {
        Counts adSet  = new Counts(2_000, 150, 12, 6_000);   // $500 per held call, 0.6 % held/click
        Counts anchor = new Counts(2_000, 150, 60, 6_000);   // $100 per held call, 3 % held/click
        Optional<String> kill = HeldCallGate.evaluateGate3(adSet, anchor);
        assertThat(kill).isPresent();
    }

    @Test
    void cheaperHeldCallsNeverKilledOnCost() {
        Counts adSet  = new Counts(2_000, 150, 60, 3_000);   // $50 per held call
        Counts anchor = new Counts(2_000, 150, 60, 6_000);   // $100 per held call
        assertThat(HeldCallGate.evaluateGate3(adSet, anchor)).isEmpty();
    }

    @Test
    void tooFewBookingsIsNotADecision() {
        Counts adSet  = new Counts(1_000, 5, 1, 500);
        Counts anchor = new Counts(1_000, 100, 60, 2_000);
        assertThat(HeldCallGate.evaluateGate3(adSet, anchor)).isEmpty();
        assertThat(HeldCallGate.hasEnoughData(adSet)).isFalse();
    }

    @Test
    void bookingRateProbabilityNeedsClicks() {
        assertThat(HeldCallGate.probBookingRateBetter(new Counts(10, 2, 0, 0), new Counts(500, 50, 0, 0))).isEmpty();
        Optional<Double> p = HeldCallGate.probBookingRateBetter(new Counts(500, 80, 0, 0), new Counts(2_000, 100, 0, 0));
        assertThat(p).isPresent();
        assertThat(p.get()).isGreaterThan(0.95);   // 16 % vs 5 % booking rate
    }

    @Test
    void costPerHeldIsInfiniteWithoutHeldCalls() {
        assertThat(new Counts(100, 10, 0, 500).costPerHeld()).isInfinite();
        assertThat(new Counts(100, 10, 5, 500).costPerHeld()).isEqualTo(100.0);
    }
}
