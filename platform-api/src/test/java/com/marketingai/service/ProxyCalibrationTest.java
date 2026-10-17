package com.marketingai.service;

import com.marketingai.scheduler.ProxyCalibrationScheduler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyCalibrationTest {

    @Test
    void pearson_perfect_correlation() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {1, 2, 3, 4, 5};
        double r = ProxyCalibrationScheduler.pearson(x, y);
        assertThat(r).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void pearson_perfect_anti_correlation() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {5, 4, 3, 2, 1};
        double r = ProxyCalibrationScheduler.pearson(x, y);
        assertThat(r).isCloseTo(-1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void pearson_no_correlation() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {3, 3, 3, 3, 3};
        double r = ProxyCalibrationScheduler.pearson(x, y);
        assertThat(r).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void spearman_monotonic_relationship() {
        double[] x = {1, 2, 3, 4, 5};
        double[] y = {2, 4, 6, 8, 10};
        double r = ProxyCalibrationScheduler.spearman(x, y);
        assertThat(r).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }
}
