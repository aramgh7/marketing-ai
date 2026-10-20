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
    void unknownStageFallsBackToConversion() {
        assertThat(RewardService.rewardTypeForStage("ACCRED_VERIFIED")).isEqualTo("CONVERSION");
        assertThat(RewardService.rewardTypeForStage(null)).isEqualTo("CONVERSION");
    }
}
