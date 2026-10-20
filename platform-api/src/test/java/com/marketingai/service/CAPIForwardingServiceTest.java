package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CAPIForwardingServiceTest {

    // ─── 4.6: unknown send_condition must fail closed ───

    @Test
    void knownConditionsEvaluate() {
        assertThat(CAPIForwardingService.evaluateCondition("self_accredited = true", true)).isTrue();
        assertThat(CAPIForwardingService.evaluateCondition("self_accredited = true", false)).isFalse();
        assertThat(CAPIForwardingService.evaluateCondition("self_accredited = false", false)).isTrue();
        assertThat(CAPIForwardingService.evaluateCondition("SELF_ACCREDITED = TRUE ", true)).isTrue();
    }

    @Test
    void blankConditionAlwaysSends() {
        assertThat(CAPIForwardingService.evaluateCondition(null, null)).isTrue();
        assertThat(CAPIForwardingService.evaluateCondition("  ", null)).isTrue();
    }

    @Test
    void unknownConditionFailsClosed() {
        // a typo in capi_event_map.send_condition used to silently disable the gate
        assertThat(CAPIForwardingService.evaluateCondition("self_acredited = true", true)).isFalse();
        assertThat(CAPIForwardingService.evaluateCondition("ticket_bucket = 'high'", true)).isFalse();
    }

    // ─── 4.4: deterministic event_id for pixel/CAPI dedup ───

    @Test
    void eventIdIsDeterministicAndScoped() {
        UUID t = UUID.randomUUID();
        String a = CAPIForwardingService.deterministicEventId(t, "appt-1", "CALL_HELD");
        assertThat(a).isEqualTo(CAPIForwardingService.deterministicEventId(t, "appt-1", "CALL_HELD"));
        assertThat(a).hasSize(64).matches("[0-9a-f]+");
        assertThat(a).isNotEqualTo(CAPIForwardingService.deterministicEventId(t, "appt-1", "BOOKED"));
        assertThat(a).isNotEqualTo(CAPIForwardingService.deterministicEventId(UUID.randomUUID(), "appt-1", "CALL_HELD"));
    }

    @Test
    void eventIdNullWithoutAnchor() {
        assertThat(CAPIForwardingService.deterministicEventId(UUID.randomUUID(), null, "CALL_HELD")).isNull();
        assertThat(CAPIForwardingService.deterministicEventId(UUID.randomUUID(), " ", "CALL_HELD")).isNull();
    }

    // ─── 4.5: retry backoff ───

    @Test
    void firstAttemptIsDueImmediately() {
        assertThat(CAPIForwardingService.retryDue(0, null, Instant.now())).isTrue();
    }

    @Test
    void backoffDoublesPerAttempt() {
        Instant now = Instant.now();
        Instant last = now.minus(Duration.ofMinutes(12));
        assertThat(CAPIForwardingService.retryDue(1, last, now)).isTrue();   // 10 min backoff
        assertThat(CAPIForwardingService.retryDue(2, last, now)).isFalse();  // 20 min backoff
        assertThat(CAPIForwardingService.retryDue(2, now.minus(Duration.ofMinutes(21)), now)).isTrue();
    }

    @Test
    void backoffIsCapped() {
        Instant now = Instant.now();
        assertThat(CAPIForwardingService.retryDue(20, now.minus(Duration.ofHours(7)), now)).isTrue();
        assertThat(CAPIForwardingService.retryDue(20, now.minus(Duration.ofHours(5)), now)).isFalse();
    }
}
