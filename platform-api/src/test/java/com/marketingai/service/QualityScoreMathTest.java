package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Roadmap 8-3 (2-12 change): show_rate is the heaviest Quality Score component for appointment funnels. */
class QualityScoreMathTest {

    @Test
    void baseWeightsWithoutAppointmentOutcome() {
        assertThat(QualityScoreMath.score(1.0, false, 1.0, 1.0, null)).isEqualTo(1.0);
        assertThat(QualityScoreMath.score(0.0, true, 0.0, 0.0, null)).isEqualTo(0.0);
        assertThat(QualityScoreMath.score(null, false, 1.0, 1.0, null)).isCloseTo(0.875, within(1e-6)); // neutral 0.5 match
    }

    @Test
    void heldCallDominatesTheScore() {
        double held   = QualityScoreMath.score(0.5, false, 0.5, 1.0, 1.0);
        double noShow = QualityScoreMath.score(0.5, false, 0.5, 1.0, 0.0);
        assertThat(held - noShow).isCloseTo(QualityScoreMath.SHOW_WEIGHT, within(1e-6));
        assertThat(held).isGreaterThan(noShow);
    }

    @Test
    void perfectSessionThatNoShowedScoresBelowAverageSessionThatShowed() {
        double perfectNoShow = QualityScoreMath.score(1.0, false, 1.0, 1.0, 0.0);   // 0.60
        double averageShowed = QualityScoreMath.score(0.5, false, 0.5, 0.3, 1.0);   // 0.15*(0.5+1+0.5+0.3)+0.4 = 0.745
        assertThat(averageShowed).isGreaterThan(perfectNoShow);
    }

    @Test
    void clampedToUnitInterval() {
        assertThat(QualityScoreMath.score(5.0, false, 9.0, 9.0, 9.0)).isEqualTo(1.0);
        assertThat(QualityScoreMath.score(-5.0, true, -1.0, -1.0, -1.0)).isEqualTo(0.0);
    }
}
