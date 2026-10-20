package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-9 / arch §15.9: persona_mode resolution, arm alternation, promotion rule. */
class PersonaModeMathTest {

    @Test
    void resolveMode_defaults_and_explicit() {
        assertThat(PersonaModeMath.resolveMode(null, false)).isEqualTo("OFF");
        assertThat(PersonaModeMath.resolveMode(null, true)).isEqualTo("AB_TEST");
        assertThat(PersonaModeMath.resolveMode("prefilter", false)).isEqualTo("PREFILTER");
        assertThat(PersonaModeMath.resolveMode("bogus", true)).isEqualTo("AB_TEST");
    }

    @Test
    void arm_alternates_under_ab_test_only() {
        assertThat(PersonaModeMath.arm("AB_TEST", 0)).isEqualTo("PREFILTER");
        assertThat(PersonaModeMath.arm("AB_TEST", 1)).isEqualTo("CONTROL");
        assertThat(PersonaModeMath.arm("AB_TEST", 2)).isEqualTo("PREFILTER");
        assertThat(PersonaModeMath.arm("PREFILTER", 7)).isEqualTo("PREFILTER");
        assertThat(PersonaModeMath.arm("LOG_ONLY", 0)).isNull();
        assertThat(PersonaModeMath.arm("OFF", 0)).isNull();
    }

    @Test
    void promotion_needs_batches_days_and_probability() {
        assertThat(PersonaModeMath.evaluate(15, 19, 5, 25, 120).promote()).isFalse();   // < 20 per arm
        assertThat(PersonaModeMath.evaluate(18, 25, 5, 25, 60).promote()).isFalse();    // < 90 days
        PersonaModeMath.Decision d = PersonaModeMath.evaluate(18, 25, 5, 25, 120, new Random(1));
        assertThat(d.promote()).isTrue();
        assertThat(d.pBetter()).isGreaterThan(0.9);
        PersonaModeMath.Decision tie = PersonaModeMath.evaluate(12, 25, 12, 25, 120, new Random(1));
        assertThat(tie.promote()).isFalse();
        assertThat(tie.pBetter()).isBetween(0.3, 0.7);
    }

    @Test
    void worse_prefilter_never_promotes() {
        PersonaModeMath.Decision d = PersonaModeMath.evaluate(5, 30, 20, 30, 200, new Random(3));
        assertThat(d.promote()).isFalse();
        assertThat(d.pBetter()).isLessThan(0.1);
    }

    @Test
    void beta_sampler_is_in_unit_interval() {
        Random rng = new Random(7);
        for (int i = 0; i < 1000; i++) {
            double x = PersonaModeMath.sampleBeta(0.5 + i % 3, 1 + i % 5, rng);
            assertThat(x).isBetween(0.0, 1.0);
        }
    }
}
