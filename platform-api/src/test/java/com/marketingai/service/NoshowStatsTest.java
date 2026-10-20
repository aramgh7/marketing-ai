package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-3: weekly report line "no-show rate with credible interval vs target < 30 %". */
class NoshowStatsTest {

    @Test
    void rateAndIntervalBracketTheObservedShare() {
        NoshowStats.Summary s = NoshowStats.summarise(20, 80, null);
        assertThat(s.rate()).isEqualTo(0.2);
        assertThat(s.low()).isLessThan(0.2);
        assertThat(s.high()).isGreaterThan(0.2);
        assertThat(s.target()).isEqualTo(NoshowStats.DEFAULT_TARGET);
    }

    @Test
    void clearlyWithinTarget() {
        NoshowStats.Summary s = NoshowStats.summarise(10, 190, 0.30);   // 5 %
        assertThat(s.meetsTarget()).isTrue();
        assertThat(s.verdict()).isEqualTo("within target");
    }

    @Test
    void clearlyAboveTarget() {
        NoshowStats.Summary s = NoshowStats.summarise(100, 100, 0.30);  // 50 %
        assertThat(s.missesTarget()).isTrue();
        assertThat(NoshowStats.reportLine(s)).contains("ABOVE target").contains("50.0%");
    }

    @Test
    void smallSampleIsInconclusive() {
        NoshowStats.Summary s = NoshowStats.summarise(2, 4, 0.30);      // 33 % of 6
        assertThat(s.verdict()).isEqualTo("inconclusive");
    }

    @Test
    void noDataIsReportedAsSuch() {
        NoshowStats.Summary s = NoshowStats.summarise(0, 0, 0.30);
        assertThat(NoshowStats.reportLine(s)).contains("no resolved appointments");
    }

    @Test
    void customTargetFromTenantSettings() {
        NoshowStats.Summary s = NoshowStats.summarise(30, 70, 0.20);    // 30 % vs target 20 %
        assertThat(s.target()).isEqualTo(0.20);
        assertThat(s.missesTarget()).isTrue();
    }
}
