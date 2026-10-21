package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static com.marketingai.service.MetaWritePolicy.Initiator.JOB;
import static com.marketingai.service.MetaWritePolicy.Initiator.USER;
import static com.marketingai.service.MetaWritePolicy.Stage.*;
import static com.marketingai.service.MetaWritePolicy.Verdict.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-19: decision table of the Meta write policy. */
class MetaWritePolicyTest {

    private static final MetaWritePolicy.Thresholds T = MetaWritePolicy.Thresholds.defaults();

    private static MetaWritePolicy.Context ctx(MetaWritePolicy.Stage stage, String autonomy, MetaWritePolicy.Initiator who,
                                               MetaWriteAction action) {
        return new MetaWritePolicy.Context(stage, autonomy, who, action, "adset-1", null, null, 0, "ACTIVE", false, false);
    }

    private static MetaWritePolicy.Context with(MetaWritePolicy.Context c, Double budgetPct, Double hoursSince,
                                                int writes, String phase, boolean probe, boolean override) {
        return new MetaWritePolicy.Context(c.stage(), c.autonomyLevel(), c.initiator(), c.action(), c.targetId(),
                budgetPct, hoursSince, writes, phase, probe, override);
    }

    @Test
    void protective_writes_are_never_gated() {
        for (MetaWritePolicy.Stage s : MetaWritePolicy.Stage.values()) {
            MetaWritePolicy.Context c = with(ctx(s, "OBSERVER", JOB, MetaWriteAction.PAUSE), null, null, 999, "LEARNING", false, false);
            assertThat(MetaWritePolicy.evaluate(c, T).verdict()).isEqualTo(ALLOW);
        }
    }

    @Test
    void read_only_stage_refuses_every_expansive_write_even_for_humans() {
        assertThat(MetaWritePolicy.evaluate(ctx(READ_ONLY, "AUTOPILOT", USER, MetaWriteAction.RESUME), T).verdict()).isEqualTo(DENY);
        assertThat(MetaWritePolicy.evaluate(ctx(READ_ONLY, "AUTOPILOT", USER, MetaWriteAction.LAUNCH), T).verdict()).isEqualTo(DENY);
        assertThat(MetaWritePolicy.evaluate(ctx(READ_ONLY, "AUTOPILOT", JOB, MetaWriteAction.BUDGET_CHANGE), T).reason()).contains("READ_ONLY");
    }

    @Test
    void guarded_stage_allows_launch_only_as_probe_and_no_campaign_creation() {
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "ADVISOR", USER, MetaWriteAction.LAUNCH), T).verdict()).isEqualTo(DENY);
        MetaWritePolicy.Context probe = with(ctx(GUARDED_WRITES, "ADVISOR", USER, MetaWriteAction.LAUNCH), null, null, 0, "ACTIVE", true, false);
        assertThat(MetaWritePolicy.evaluate(probe, T).verdict()).isEqualTo(ALLOW);
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "AUTOPILOT", USER, MetaWriteAction.CREATE_CAMPAIGN), T).verdict()).isEqualTo(DENY);
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "ADVISOR", USER, MetaWriteAction.RESUME), T).verdict()).isEqualTo(ALLOW);
    }

    /** Roadmap Phase 11: publishing a creative as a PAUSED ad is the safest write and needs no probe flag. */
    @Test
    void guarded_stage_allows_paused_ad_creation_without_probe_flag() {
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "ADVISOR", USER, MetaWriteAction.CREATE_AD_PAUSED), T).verdict()).isEqualTo(ALLOW);
        assertThat(MetaWritePolicy.evaluate(ctx(READ_ONLY, "AUTOPILOT", USER, MetaWriteAction.CREATE_AD_PAUSED), T).verdict()).isEqualTo(DENY);
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "OBSERVER", USER, MetaWriteAction.CREATE_AD_PAUSED), T).verdict()).isEqualTo(ALLOW);
        // LAUNCH (real ad sets) still needs the probe flag in GUARDED
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "ADVISOR", USER, MetaWriteAction.LAUNCH), T).verdict()).isEqualTo(DENY);
        // jobs: SEMI_AUTOPILOT needed, ADVISOR → recommendation
        assertThat(MetaWritePolicy.evaluate(ctx(GUARDED_WRITES, "ADVISOR", JOB, MetaWriteAction.CREATE_AD_PAUSED), T).verdict()).isEqualTo(RECOMMEND);
        assertThat(MetaWriteAction.fromLegacyName("CREATE_AD_PAUSED")).isEqualTo(MetaWriteAction.CREATE_AD_PAUSED);
        assertThat(MetaWriteAction.fromLegacyName("CREATE_AD_SET")).isEqualTo(MetaWriteAction.CREATE_AD_SET);
    }

    @Test
    void full_stage_lets_humans_do_everything_within_thresholds() {
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "OBSERVER", USER, MetaWriteAction.CREATE_CAMPAIGN), T).verdict()).isEqualTo(ALLOW);
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "OBSERVER", USER, MetaWriteAction.LAUNCH), T).verdict()).isEqualTo(ALLOW);
    }

    @Test
    void jobs_need_autonomy_level() {
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "ADVISOR", JOB, MetaWriteAction.RESUME), T).verdict()).isEqualTo(RECOMMEND);
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "SEMI_AUTOPILOT", JOB, MetaWriteAction.RESUME), T).verdict()).isEqualTo(ALLOW);
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "SEMI_AUTOPILOT", JOB, MetaWriteAction.CREATE_CAMPAIGN), T).verdict()).isEqualTo(DENY);
        assertThat(MetaWritePolicy.evaluate(ctx(FULL, "AUTOPILOT", JOB, MetaWriteAction.CREATE_CAMPAIGN), T).verdict()).isEqualTo(ALLOW);
    }

    @Test
    void learning_phase_lock_blocks_budget_and_resume_but_not_pause() {
        MetaWritePolicy.Context budget = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.BUDGET_CHANGE), 10.0, null, 0, "LEARNING", false, false);
        assertThat(MetaWritePolicy.evaluate(budget, T).verdict()).isEqualTo(DENY);
        MetaWritePolicy.Context resume = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.RESUME), null, null, 0, "LEARNING", false, false);
        assertThat(MetaWritePolicy.evaluate(resume, T).verdict()).isEqualTo(DENY);
        MetaWritePolicy.Context pause = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.PAUSE), null, null, 0, "LEARNING", false, false);
        assertThat(MetaWritePolicy.evaluate(pause, T).verdict()).isEqualTo(ALLOW);
        // lock can be switched off per tenant
        MetaWritePolicy.Thresholds noLock = new MetaWritePolicy.Thresholds(20, 24, 10, false, true);
        assertThat(MetaWritePolicy.evaluate(budget, noLock).verdict()).isEqualTo(ALLOW);
    }

    @Test
    void budget_thresholds_turn_into_recommendations() {
        MetaWritePolicy.Context big = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.BUDGET_CHANGE), 35.0, 48.0, 0, "ACTIVE", false, false);
        assertThat(MetaWritePolicy.evaluate(big, T).verdict()).isEqualTo(RECOMMEND);
        MetaWritePolicy.Context soon = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.BUDGET_CHANGE), 10.0, 3.0, 0, "ACTIVE", false, false);
        assertThat(MetaWritePolicy.evaluate(soon, T).verdict()).isEqualTo(RECOMMEND);
        MetaWritePolicy.Context ok = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.BUDGET_CHANGE), -15.0, 30.0, 2, "ACTIVE", false, false);
        assertThat(MetaWritePolicy.evaluate(ok, T).verdict()).isEqualTo(ALLOW);
    }

    @Test
    void rate_limit_per_hour() {
        MetaWritePolicy.Context c = with(ctx(FULL, "AUTOPILOT", USER, MetaWriteAction.RESUME), null, null, 10, "ACTIVE", false, false);
        assertThat(MetaWritePolicy.evaluate(c, T).verdict()).isEqualTo(RECOMMEND);
        assertThat(MetaWritePolicy.evaluate(with(c, null, null, 9, "ACTIVE", false, false), T).verdict()).isEqualTo(ALLOW);
    }

    @Test
    void override_beats_stage_but_not_learning_lock_for_budget() {
        MetaWritePolicy.Context launch = with(ctx(READ_ONLY, "OBSERVER", USER, MetaWriteAction.LAUNCH), null, null, 50, "ACTIVE", false, true);
        assertThat(MetaWritePolicy.evaluate(launch, T).verdict()).isEqualTo(ALLOW);
        MetaWritePolicy.Context budget = with(ctx(READ_ONLY, "OBSERVER", USER, MetaWriteAction.BUDGET_CHANGE), 50.0, null, 0, "LEARNING", false, true);
        assertThat(MetaWritePolicy.evaluate(budget, T).verdict()).isEqualTo(DENY);
    }

    @Test
    void legacy_action_names_map_to_classes() {
        assertThat(MetaWriteAction.fromLegacyName("AUTOPAUSE_CVR_COLLAPSE")).isEqualTo(MetaWriteAction.PAUSE);
        assertThat(MetaWriteAction.fromLegacyName("RESUME_AFTER_INCIDENT")).isEqualTo(MetaWriteAction.RESUME);
        assertThat(MetaWriteAction.fromLegacyName("CB_RESUME")).isEqualTo(MetaWriteAction.RESUME);
        assertThat(MetaWriteAction.fromLegacyName("BUDGET_UPDATE")).isEqualTo(MetaWriteAction.BUDGET_CHANGE);
        assertThat(MetaWriteAction.fromLegacyName("CAMPAIGN_CREATE")).isEqualTo(MetaWriteAction.CREATE_CAMPAIGN);
        assertThat(MetaWriteAction.fromLegacyName("AUDIENCE_UPDATE")).isEqualTo(MetaWriteAction.AUDIENCE_UPDATE);
        assertThat(MetaWritePolicy.parseStage("guarded_writes")).isEqualTo(GUARDED_WRITES);
        assertThat(MetaWritePolicy.parseStage("nonsense")).isEqualTo(READ_ONLY);
    }
}
