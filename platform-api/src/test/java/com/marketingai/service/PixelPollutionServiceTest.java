package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for PixelPollutionService drift detection logic (vision §6.8 Level 3).
 * No Spring context — tests package-private static helpers directly.
 */
class PixelPollutionServiceTest {

    // ─── shouldAlert ────────────────────────────────────────────────────────

    @Test
    void shouldAlert_volumeTooLow_returnsFalse() {
        // forms_14d < 20 → no alert regardless of quality rate
        assertThat(PixelPollutionService.shouldAlert(0.01, 0.20, 5)).isFalse();
    }

    @Test
    void shouldAlert_nullRate_returnsFalse() {
        assertThat(PixelPollutionService.shouldAlert(null, 0.20, 30)).isFalse();
    }

    @Test
    void shouldAlert_nullBaseline_returnsFalse() {
        assertThat(PixelPollutionService.shouldAlert(0.05, null, 30)).isFalse();
    }

    @Test
    void shouldAlert_zeroBaseline_returnsFalse() {
        // Zero baseline → no historic data, avoid false positives
        assertThat(PixelPollutionService.shouldAlert(0.0, 0.0, 50)).isFalse();
    }

    @Test
    void shouldAlert_severeDrop_returnsTrue() {
        // quality drops from 20% to 5% (25% of baseline) — clear pollution
        assertThat(PixelPollutionService.shouldAlert(0.05, 0.20, 30)).isTrue();
    }

    @Test
    void shouldAlert_mildDrop_returnsFalse() {
        // quality drops from 20% to 16% (80% of baseline) — within 70% threshold
        assertThat(PixelPollutionService.shouldAlert(0.16, 0.20, 30)).isFalse();
    }

    @Test
    void shouldAlert_exactlyAtThreshold_returnsFalse() {
        // rate14 = exactly 70% of baseline → strict < means no alert
        double baseline = 0.20;
        double quality  = baseline * 0.70;
        assertThat(PixelPollutionService.shouldAlert(quality, baseline, 30)).isFalse();
    }

    @Test
    void shouldAlert_justBelowThreshold_returnsTrue() {
        double baseline = 0.20;
        double quality  = baseline * 0.699;
        assertThat(PixelPollutionService.shouldAlert(quality, baseline, 30)).isTrue();
    }

    @Test
    void shouldAlert_exactlyAtMinVolume_returnsTrue() {
        // exactly 20 forms (min) with severe quality drop → alert fires
        assertThat(PixelPollutionService.shouldAlert(0.05, 0.20, 20)).isTrue();
    }

    @Test
    void shouldAlert_negativeBaseline_returnsFalse() {
        // Defensive: negative baseline should not trigger alert
        assertThat(PixelPollutionService.shouldAlert(0.05, -0.10, 50)).isFalse();
    }

    // ─── driftPct ────────────────────────────────────────────────────────────

    @Test
    void driftPct_noChange_returnsZero() {
        assertThat(PixelPollutionService.driftPct(0.20, 0.20)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void driftPct_halfBaseline_returnsMinus50() {
        // quality = 50% of baseline → drift = -50%
        assertThat(PixelPollutionService.driftPct(0.10, 0.20)).isCloseTo(-50.0, within(0.01));
    }

    @Test
    void driftPct_improvement_returnsPositive() {
        // quality = 150% of baseline → drift = +50%
        assertThat(PixelPollutionService.driftPct(0.30, 0.20)).isCloseTo(50.0, within(0.01));
    }

    @Test
    void driftPct_severeDropToFivePercent() {
        // 5% quality vs 20% baseline → drift ≈ -75%
        assertThat(PixelPollutionService.driftPct(0.05, 0.20)).isCloseTo(-75.0, within(0.01));
    }
}
