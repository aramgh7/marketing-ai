package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-3: OPE gate decision table for autonomy changes. */
class AutonomyGateTest {

    @Test
    void downgrade_always_allowed_even_without_ope() {
        assertThat(AutonomyGate.evaluate("AUTOPILOT", "ADVISOR", null, null).allowed()).isTrue();
        assertThat(AutonomyGate.evaluate("SEMI_AUTOPILOT", "OBSERVER", "FAIL", 100L).allowed()).isTrue();
        assertThat(AutonomyGate.evaluate("ADVISOR", "ADVISOR", null, null).allowed()).isTrue();
    }

    @Test
    void observer_to_advisor_needs_no_gate() {
        assertThat(AutonomyGate.evaluate("OBSERVER", "ADVISOR", null, null).allowed()).isTrue();
    }

    @Test
    void upgrade_above_advisor_requires_recent_pass() {
        assertThat(AutonomyGate.evaluate("ADVISOR", "SEMI_AUTOPILOT", null, null).allowed()).isFalse();
        assertThat(AutonomyGate.evaluate("ADVISOR", "SEMI_AUTOPILOT", "FAIL", 1L).allowed()).isFalse();
        assertThat(AutonomyGate.evaluate("ADVISOR", "SEMI_AUTOPILOT", "INSUFFICIENT_DATA", 1L).allowed()).isFalse();
        assertThat(AutonomyGate.evaluate("ADVISOR", "SEMI_AUTOPILOT", "PASS", 8L).allowed()).isFalse();
        assertThat(AutonomyGate.evaluate("ADVISOR", "SEMI_AUTOPILOT", "PASS", 7L).allowed()).isTrue();
        assertThat(AutonomyGate.evaluate("SEMI_AUTOPILOT", "AUTOPILOT", "PASS", 0L).allowed()).isTrue();
        assertThat(AutonomyGate.evaluate("ADVISOR", "autopilot", "pass", 2L).allowed()).isTrue();
    }

    @Test
    void reasons_name_the_failing_condition() {
        assertThat(AutonomyGate.evaluate("ADVISOR", "AUTOPILOT", null, null).reason()).contains("no off-policy evaluation");
        assertThat(AutonomyGate.evaluate("ADVISOR", "AUTOPILOT", "FAIL", 1L).reason()).contains("FAIL");
        assertThat(AutonomyGate.evaluate("ADVISOR", "AUTOPILOT", "PASS", 30L).reason()).contains("30 days old");
    }

    @Test
    void invalid_level_is_refused() {
        AutonomyGate.Decision d = AutonomyGate.evaluate("ADVISOR", "GOD_MODE", "PASS", 0L);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("Invalid autonomy level");
        assertThat(AutonomyGate.isValid("semi_autopilot")).isTrue();
        assertThat(AutonomyGate.isValid(null)).isFalse();
    }

    @Test
    void failed_weekly_ope_drops_only_autonomous_tenants() {
        assertThat(AutonomyGate.levelAfterFailedOpe("AUTOPILOT")).isEqualTo("ADVISOR");
        assertThat(AutonomyGate.levelAfterFailedOpe("SEMI_AUTOPILOT")).isEqualTo("ADVISOR");
        assertThat(AutonomyGate.levelAfterFailedOpe("ADVISOR")).isEqualTo("ADVISOR");
        assertThat(AutonomyGate.levelAfterFailedOpe("OBSERVER")).isEqualTo("OBSERVER");
        assertThat(AutonomyGate.rank("unknown")).isEqualTo(AutonomyGate.rank("ADVISOR"));
    }
}
