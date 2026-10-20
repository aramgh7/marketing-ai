package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit 2.1: stage -> FastAPI reward_type mapping (must be a key of thompson_service.REWARD_MAP). */
class RewardServiceTest {

    @Test
    void heldStagesMapToMeetingAttended() {
        assertThat(RewardService.rewardTypeForStage("CALL_HELD")).isEqualTo("MEETING_ATTENDED");
        assertThat(RewardService.rewardTypeForStage("FOLLOWUP_HELD")).isEqualTo("MEETING_ATTENDED");
    }

    @Test
    void fundsReceivedMapsToPurchase() {
        assertThat(RewardService.rewardTypeForStage("FUNDS_RECEIVED")).isEqualTo("PURCHASE");
    }

    @Test
    void rewardLevelsConfig_onlyL1AndL2Reward() {
        RewardService.RewardLevels levels = new RewardService.RewardLevels("SLOT_SELECTED", "FORM_SUBMIT", "CALL_HELD");
        assertThat(RewardService.rewardTypeFor("SLOT_SELECTED", levels, false)).isEqualTo("CTA_CLICK");
        assertThat(RewardService.rewardTypeFor("FORM_SUBMIT", levels, false)).isEqualTo("FORM_SUBMIT");
        assertThat(RewardService.rewardTypeFor("CALL_HELD", levels, true)).isNull();     // corrector: no α/β
        assertThat(RewardService.rewardTypeFor("FUNDS_RECEIVED", levels, true)).isNull();
    }

    @Test
    void withoutConfig_onlyQualityStageRewards() {
        assertThat(RewardService.rewardTypeFor("CALL_HELD", null, true)).isEqualTo("MEETING_ATTENDED");
        assertThat(RewardService.rewardTypeFor("BOOKED", null, false)).isNull();
    }

    @Test
    void rewardLevelsParsedFromSettingsJson() {
        RewardService.RewardLevels levels = RewardService.RewardLevels.parse(
                "{\"L1\":\"SLOT_SELECTED\",\"L2\":\"FORM_SUBMIT\",\"corrector\":\"CALL_HELD\"}");
        assertThat(levels.l1()).isEqualTo("SLOT_SELECTED");
        assertThat(RewardService.RewardLevels.parse(null)).isNull();
        assertThat(RewardService.RewardLevels.parse("{}")).isNull();
    }

    @Test
    void unknownStageFallsBackToConversion() {
        assertThat(RewardService.rewardTypeForStage("ACCRED_VERIFIED")).isEqualTo("CONVERSION");
        assertThat(RewardService.rewardTypeForStage(null)).isEqualTo("CONVERSION");
    }
}
