package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Roadmap 9-8: pure helpers of the six remaining jobs (CVR collapse shaping, MMF, calibration, stage value, slots, moderation, Reg CF). */
class Phase9JobsMathTest {

    // ─── CvrCollapseMath ───
    @Test
    void denseSeries_fills_missing_hours_with_zeros_in_order() {
        Instant now = Instant.parse("2026-10-21T15:40:00Z");
        List<Map<String, Object>> s = CvrCollapseMath.denseSeries(List.of(
                new CvrCollapseMath.HourRow(Instant.parse("2026-10-21T15:10:00Z"), 120, 0),
                new CvrCollapseMath.HourRow(Instant.parse("2026-10-21T13:00:00Z"), 100, 5)), now);
        assertThat(s).hasSize(24);
        assertThat(s.get(23).get("hour")).isEqualTo("2026-10-21T15:00:00Z");
        assertThat(s.get(23).get("sessions")).isEqualTo(120L);
        assertThat(s.get(21).get("conversions")).isEqualTo(5L);
        assertThat(s.get(22).get("sessions")).isEqualTo(0L);
        assertThat(s.get(0).get("hour")).isEqualTo("2026-10-20T16:00:00Z");
        assertThat(CvrCollapseMath.worthChecking(s, 200)).isTrue();
        assertThat(CvrCollapseMath.worthChecking(s, 500)).isFalse();
    }

    // ─── MmfScanMath ───
    @Test
    void mmf_metrics_are_safe_ratios_with_defaults() {
        Map<String, Object> m = MmfScanMath.metrics(10_000, 200, 10, 300.0, 40.0, 100.0, "LEARNING", null);
        assertThat((Double) m.get("ctr")).isCloseTo(0.02, within(1e-9));
        assertThat((Double) m.get("cvr")).isCloseTo(0.05, within(1e-9));
        assertThat((Double) m.get("cpc")).isCloseTo(1.5, within(1e-9));
        assertThat((Double) m.get("budget_util")).isCloseTo(0.4, within(1e-9));
        assertThat(m.get("roas")).isEqualTo(2.0);
        assertThat(m.get("learning_phase")).isEqualTo("LEARNING");
        Map<String, Object> zero = MmfScanMath.metrics(0, 0, 0, 0, 0, null, null, 3.1);
        assertThat(zero.get("ctr")).isEqualTo(0.0);
        assertThat(zero.get("budget_util")).isEqualTo(0.5);
        assertThat(zero.get("roas")).isEqualTo(3.1);
        assertThat(MmfScanMath.enoughData(999, 100)).isFalse();
        assertThat(MmfScanMath.enoughData(5000, 29)).isFalse();
        assertThat(MmfScanMath.enoughData(5000, 30)).isTrue();
    }

    // ─── SelfReportCalibrationMath ───
    @Test
    void k_accr_scales_down_below_trust_rate_with_floor() {
        assertThat(SelfReportCalibrationMath.kAccr(0.9)).isEqualTo(1.0);
        assertThat(SelfReportCalibrationMath.kAccr(0.5)).isEqualTo(1.0);
        assertThat(SelfReportCalibrationMath.kAccr(0.4)).isCloseTo(0.5, within(1e-9));
        assertThat(SelfReportCalibrationMath.kAccr(0.05)).isEqualTo(0.2);
        assertThat(SelfReportCalibrationMath.kAccr(0.0)).isEqualTo(0.2);
    }

    @Test
    void calibration_table_skips_small_cells_and_keys_by_creative_and_bucket() {
        Map<String, Map<String, Object>> t = SelfReportCalibrationMath.build(List.of(
                new SelfReportCalibrationMath.Cell("c1", "50_100K", 40, 8),
                new SelfReportCalibrationMath.Cell("c1", null, 25, 20),
                new SelfReportCalibrationMath.Cell("c2", "LT25K", 19, 19),
                new SelfReportCalibrationMath.Cell(null, "LT25K", 50, 50)));
        assertThat(t).containsOnlyKeys("c1|50_100K", "c1|ALL");
        assertThat(t.get("c1|50_100K").get("rate")).isEqualTo(0.2);
        assertThat(t.get("c1|50_100K").get("k_accr")).isEqualTo(0.25);
        assertThat(t.get("c1|ALL").get("k_accr")).isEqualTo(1.0);
    }

    // ─── StageValueMath ───
    @Test
    void stage_values_need_20_closed_deals_and_divide_by_reach() {
        List<Double> closed = java.util.Collections.nCopies(20, 50_000.0);
        Map<String, StageValueMath.StageValue> v = StageValueMath.expectedValues(
                Map.of("FORM_SUBMIT", 1000L, "CALL_HELD", 100L, "FUNDS_RECEIVED", 5L, "EMPTY", 0L), closed);
        assertThat(v.get("FORM_SUBMIT").expectedValueUsd()).isEqualTo(1000.0);
        assertThat(v.get("CALL_HELD").expectedValueUsd()).isEqualTo(10_000.0);
        assertThat(v.get("FUNDS_RECEIVED").expectedValueUsd()).isEqualTo(50_000.0);   // reach < closed → capped
        assertThat(v).doesNotContainKey("EMPTY");
        assertThat(StageValueMath.expectedValues(Map.of("FORM_SUBMIT", 10L), closed.subList(0, 19))).isEmpty();
    }

    // ─── SlotCapacityMath ───
    @Test
    void overbook_is_floor_of_expected_noshows_capped_at_two() {
        assertThat(SlotCapacityMath.overbook(0.30, 4.0)).isEqualTo(1);
        assertThat(SlotCapacityMath.overbook(0.50, 3.0)).isEqualTo(1);
        assertThat(SlotCapacityMath.overbook(0.60, 10.0)).isEqualTo(2);
        assertThat(SlotCapacityMath.overbook(0.10, 4.0)).isEqualTo(0);
        assertThat(SlotCapacityMath.overbook(0.0, 4.0)).isEqualTo(0);
        SlotCapacityMath.NoShowMix mix = new SlotCapacityMath.NoShowMix(21, 9);
        assertThat(mix.pNoShow()).isCloseTo(0.3, within(1e-9));
        assertThat(SlotCapacityMath.enoughData(mix)).isTrue();
        assertThat(SlotCapacityMath.enoughData(new SlotCapacityMath.NoShowMix(20, 9))).isFalse();
    }

    // ─── ModerationProbeMath ───
    @Test
    void moderation_verdicts() {
        assertThat(ModerationProbeMath.verdict("PAUSED", false)).isEqualTo("APPROVED");
        assertThat(ModerationProbeMath.verdict("ACTIVE", false)).isEqualTo("APPROVED");
        assertThat(ModerationProbeMath.verdict("PENDING_REVIEW", false)).isEqualTo("PENDING");
        assertThat(ModerationProbeMath.verdict("PENDING_REVIEW", true)).isEqualTo("PENDING");
        assertThat(ModerationProbeMath.verdict("DISAPPROVED", false)).isEqualTo("REJECTED");
        assertThat(ModerationProbeMath.verdict("WITH_ISSUES", false)).isEqualTo("REJECTED");
        assertThat(ModerationProbeMath.verdict("PAUSED", true)).isEqualTo("REJECTED");
        assertThat(ModerationProbeMath.verdict(null, false)).isEqualTo("PENDING");
        assertThat(ModerationProbeMath.rejectionCode("DISAPPROVED", "{\"personal_attributes\": \"...\"}"))
                .isEqualTo("personal_attributes");
        assertThat(ModerationProbeMath.rejectionCode("DISAPPROVED", "{}")).isEqualTo("DISAPPROVED");
    }

    // ─── RegCfWaitlistMath ───
    @Test
    void regcf_estimate_point_and_interval() {
        RegCfWaitlistMath.Estimate e = RegCfWaitlistMath.estimate(200, 0.05, 10_000);
        assertThat(e.value()).isEqualTo(100_000.0);
        assertThat(e.ciLower()).isLessThan(e.value());
        assertThat(e.ciUpper()).isGreaterThan(e.value());
        assertThat(e.ciLower()).isGreaterThanOrEqualTo(0.0);
        assertThat(RegCfWaitlistMath.estimate(0, 0.05, 10_000).value()).isEqualTo(0.0);
    }
}
