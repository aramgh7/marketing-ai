package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-9: BOOKED vs Schedule vs FORM_SUBMIT daily reconciliation. */
class FunnelIntegrityMathTest {

    @Test
    void healthyFunnelIsOk() {
        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(120, 40, 39, null);
        assertThat(r.verdict()).isEqualTo("OK");
        assertThat(r.alert()).isFalse();
        assertThat(r.deltaBookedSchedule()).isLessThan(0.10);
    }

    @Test
    void scheduleLeakAbove10PercentIsDrift() {
        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(120, 40, 30, null);   // 25 % of bookings never reached Meta
        assertThat(r.verdict()).isEqualTo("DRIFT");
        assertThat(r.alert()).isTrue();
        assertThat(FunnelIntegrityMath.describe(r)).contains("DRIFT").contains("BOOKED=40");
    }

    @Test
    void bookingsWithoutLeadsIsDrift() {
        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(20, 40, 40, null);     // bookings we never saw as form submits
        assertThat(r.verdict()).isEqualTo("DRIFT");
    }

    @Test
    void moreFormsThanBookingsIsNormal() {
        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(300, 40, 40, null);
        assertThat(r.verdict()).isEqualTo("OK");
    }

    @Test
    void tinyVolumeIsNoData() {
        FunnelIntegrityMath.Result r = FunnelIntegrityMath.evaluate(3, 2, 0, null);
        assertThat(r.verdict()).isEqualTo("NO_DATA");
        assertThat(r.alert()).isFalse();
    }

    @Test
    void customThreshold() {
        assertThat(FunnelIntegrityMath.evaluate(100, 40, 37, 0.05).verdict()).isEqualTo("DRIFT");   // 7.5 % > 5 %
        assertThat(FunnelIntegrityMath.evaluate(100, 40, 37, 0.10).verdict()).isEqualTo("OK");
    }

    @Test
    void relativeDeltaEdgeCases() {
        assertThat(FunnelIntegrityMath.relativeDelta(0, 0)).isNull();
        assertThat(FunnelIntegrityMath.relativeDelta(10, 0)).isEqualTo(1.0);
        assertThat(FunnelIntegrityMath.relativeDelta(50, 45)).isEqualTo(0.1);
    }
}
