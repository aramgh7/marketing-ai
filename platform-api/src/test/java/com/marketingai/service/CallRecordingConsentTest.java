package com.marketingai.service;

import com.marketingai.service.CallRecordingConsent.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-18: transcripts only with recording consent per US state. */
class CallRecordingConsentTest {

    @Test
    void explicitConsentAlwaysWins() {
        assertThat(CallRecordingConsent.decide("CA", true, true)).isEqualTo(Decision.GRANTED);
        assertThat(CallRecordingConsent.decide(null, true, true)).isEqualTo(Decision.GRANTED);
    }

    @Test
    void allPartyStateWithoutConsentIsBlocked() {
        for (String s : new String[]{"CA", "fl", "Pa", "WA"}) {
            Decision d = CallRecordingConsent.decide(s, false, true);
            assertThat(d).as(s).isEqualTo(Decision.REQUIRED_MISSING);
            assertThat(CallRecordingConsent.mayTranscribe(d)).isFalse();
        }
    }

    @Test
    void onePartyStateAndInternationalNeedNoFlag() {
        assertThat(CallRecordingConsent.decide("TX", null, true)).isEqualTo(Decision.NOT_REQUIRED);
        assertThat(CallRecordingConsent.decide("NY", false, true)).isEqualTo(Decision.NOT_REQUIRED);
        assertThat(CallRecordingConsent.decide("INTL", null, true)).isEqualTo(Decision.NOT_REQUIRED);
        assertThat(CallRecordingConsent.mayTranscribe(Decision.NOT_REQUIRED)).isTrue();
    }

    @Test
    void unknownStateIsConservativeByDefault() {
        assertThat(CallRecordingConsent.decide(null, null, true)).isEqualTo(Decision.UNKNOWN);
        assertThat(CallRecordingConsent.mayTranscribe(Decision.UNKNOWN)).isFalse();
        assertThat(CallRecordingConsent.decide("", null, false)).isEqualTo(Decision.NOT_REQUIRED);
    }
}
