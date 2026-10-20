package com.marketingai.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Audit §3.4: POST /webhooks/ghl/{tenantId} accepted anything; it now requires a shared secret. */
class GhlWebhookSecretTest {

    @Test
    void matchingSecretAccepted() {
        assertThat(GhlWebhookController.secretMatches("s3cret", "s3cret")).isTrue();
    }

    @Test
    void wrongOrMissingSecretRejected() {
        assertThat(GhlWebhookController.secretMatches("wrong", "s3cret")).isFalse();
        assertThat(GhlWebhookController.secretMatches(null, "s3cret")).isFalse();
        assertThat(GhlWebhookController.secretMatches("", "s3cret")).isFalse();
    }

    @Test
    void noConfiguredSecret_failsClosed() {
        assertThat(GhlWebhookController.secretMatches("anything", null)).isFalse();
        assertThat(GhlWebhookController.secretMatches("anything", "")).isFalse();
        assertThat(GhlWebhookController.secretMatches("", "")).isFalse();
    }
}
