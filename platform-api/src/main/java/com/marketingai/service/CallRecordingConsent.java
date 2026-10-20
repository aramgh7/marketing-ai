package com.marketingai.service;

import java.util.Set;

/**
 * Recording-consent rule for call recordings (roadmap 8-18: "requires GHL recording consent per US
 * state"). All-party-consent states need an explicit consent flag from the recording system;
 * one-party states and non-US leads only need the tenant's own consent, which the coordinator
 * gives by enabling recordings. Pure, unit-tested.
 */
public final class CallRecordingConsent {

    private CallRecordingConsent() {}

    /** US all-party (two-party) consent states as of 2026. */
    public static final Set<String> ALL_PARTY_STATES = Set.of(
            "CA", "CT", "DE", "FL", "IL", "MD", "MA", "MI", "MT", "NV", "NH", "OR", "PA", "WA");

    public enum Decision {
        GRANTED,          // consent recorded by the provider / coordinator
        NOT_REQUIRED,     // one-party state or outside the US
        REQUIRED_MISSING, // all-party state without a consent flag → do not transcribe
        UNKNOWN           // state unknown and no consent flag → do not transcribe (conservative)
    }

    /**
     * @param stateCode       two-letter US state of the lead (null/blank = unknown; "XX"/"INTL" = outside US)
     * @param consentGranted  provider/coordinator flag that all parties consented
     * @param requireConsentWhenUnknown tenant setting (default true): unknown state ⇒ consent needed
     */
    public static Decision decide(String stateCode, Boolean consentGranted, boolean requireConsentWhenUnknown) {
        if (Boolean.TRUE.equals(consentGranted)) return Decision.GRANTED;
        if (stateCode == null || stateCode.isBlank()) {
            return requireConsentWhenUnknown ? Decision.UNKNOWN : Decision.NOT_REQUIRED;
        }
        String s = stateCode.trim().toUpperCase();
        if (ALL_PARTY_STATES.contains(s)) return Decision.REQUIRED_MISSING;
        return Decision.NOT_REQUIRED;
    }

    public static boolean mayTranscribe(Decision d) {
        return d == Decision.GRANTED || d == Decision.NOT_REQUIRED;
    }
}
