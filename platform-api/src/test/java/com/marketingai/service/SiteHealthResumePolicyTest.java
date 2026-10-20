package com.marketingai.service;

import com.marketingai.service.SiteHealthResumeService.Policy;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 3-13: resume only after 3 consecutive passes AND marketer confirmation. */
class SiteHealthResumePolicyTest {

    private static final Instant NOW = Instant.now();

    @Test
    void passesAloneDoNotResume() {
        assertThat(Policy.canResume(3, null)).isFalse();
        assertThat(Policy.canResume(10, null)).isFalse();
    }

    @Test
    void confirmationAloneDoesNotResume() {
        assertThat(Policy.canResume(2, NOW)).isFalse();
    }

    @Test
    void bothTogetherResume() {
        assertThat(Policy.canResume(3, NOW)).isTrue();
    }

    @Test
    void askOnceWhenThresholdReached() {
        assertThat(Policy.shouldAskForConfirmation(3, null, null)).isTrue();
        assertThat(Policy.shouldAskForConfirmation(3, null, NOW)).isFalse();   // already asked
        assertThat(Policy.shouldAskForConfirmation(3, NOW, null)).isFalse();   // already confirmed
        assertThat(Policy.shouldAskForConfirmation(2, null, null)).isFalse();
    }

    @Test
    void failResetsConsecutivePasses() {
        assertThat(Policy.nextPassCount(2, true)).isEqualTo(3);
        assertThat(Policy.nextPassCount(2, false)).isEqualTo(0);
    }
}
