package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-9 / arch §15.13: expert rating → exploration prior shift. */
class ExpertPriorMathTest {

    @Test
    void rating_table() {
        assertThat(ExpertPriorMath.shift(1, false)).isEqualTo(new ExpertPriorMath.Prior(1, 3));
        assertThat(ExpertPriorMath.shift(2, false)).isEqualTo(new ExpertPriorMath.Prior(1, 2));
        assertThat(ExpertPriorMath.shift(3, false)).isEqualTo(new ExpertPriorMath.Prior(1, 1));
        assertThat(ExpertPriorMath.shift(4, false)).isEqualTo(new ExpertPriorMath.Prior(2, 1));
        assertThat(ExpertPriorMath.shift(5, false)).isEqualTo(new ExpertPriorMath.Prior(3, 1));
    }

    @Test
    void veto_and_clamping() {
        assertThat(ExpertPriorMath.shift(5, true)).isEqualTo(new ExpertPriorMath.Prior(1, 5));
        assertThat(ExpertPriorMath.shift(0, false)).isEqualTo(new ExpertPriorMath.Prior(1, 3));
        assertThat(ExpertPriorMath.shift(9, false)).isEqualTo(new ExpertPriorMath.Prior(3, 1));
    }
}
