package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-9 / arch §15.19: capi_value_mode resolution, AB alternation, weekly comparison. */
class CapiValueModeMathTest {

    @Test
    void resolve_and_effective_mode() {
        assertThat(CapiValueModeMath.resolveMode(null)).isEqualTo("PROXY");
        assertThat(CapiValueModeMath.resolveMode("fixed")).isEqualTo("FIXED");
        assertThat(CapiValueModeMath.resolveMode("weird")).isEqualTo("PROXY");
        assertThat(CapiValueModeMath.effectiveMode("PROXY", "FIXED")).isEqualTo("PROXY");   // tenant mode wins
        assertThat(CapiValueModeMath.effectiveMode("FIXED", null)).isEqualTo("FIXED");
        assertThat(CapiValueModeMath.effectiveMode("AB", "FIXED")).isEqualTo("FIXED");
        assertThat(CapiValueModeMath.effectiveMode("AB", "PROXY")).isEqualTo("PROXY");
        assertThat(CapiValueModeMath.effectiveMode("AB", null)).isEqualTo("PROXY");         // unresolvable → PROXY
        assertThat(CapiValueModeMath.effectiveMode("AB", "AB")).isEqualTo("PROXY");
    }

    @Test
    void alternate_assignment_is_balanced() {
        assertThat(CapiValueModeMath.alternate(0)).isEqualTo("PROXY");
        assertThat(CapiValueModeMath.alternate(1)).isEqualTo("FIXED");
        assertThat(CapiValueModeMath.alternate(10)).isEqualTo("PROXY");
    }

    @Test
    void comparison_needs_min_held_per_arm() {
        CapiValueModeMath.Comparison c = CapiValueModeMath.compare(1000, 5, 1000, 20);
        assertThat(c.winner()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(c.proxyCostPerHeld()).isEqualTo(200.0);
        assertThat(c.liftPct()).isNull();
        assertThat(CapiValueModeMath.compare(1000, 0, 1000, 20).proxyCostPerHeld()).isNull();
    }

    @Test
    void comparison_picks_cheaper_arm_outside_tie_band() {
        CapiValueModeMath.Comparison proxyWins = CapiValueModeMath.compare(1000, 20, 1500, 20);
        assertThat(proxyWins.winner()).isEqualTo("PROXY");
        assertThat(proxyWins.liftPct()).isCloseTo(33.33, org.assertj.core.data.Offset.offset(0.1));
        CapiValueModeMath.Comparison fixedWins = CapiValueModeMath.compare(1500, 20, 1000, 20);
        assertThat(fixedWins.winner()).isEqualTo("FIXED");
        assertThat(fixedWins.liftPct()).isNegative();
        CapiValueModeMath.Comparison tie = CapiValueModeMath.compare(1020, 20, 1000, 20);
        assertThat(tie.winner()).isEqualTo("TIE");
    }
}
