package com.marketingai.service;

import java.util.Locale;
import java.util.Set;

/**
 * Pure verdict mapping for the moderation probe poll (roadmap 9-8; arch §16.17):
 * Meta {@code effective_status} + {@code ad_review_feedback} → APPROVED / REJECTED / PENDING.
 * The probe ad set is launched PAUSED, so a paused-but-reviewed ad counts as APPROVED.
 */
public final class ModerationProbeMath {

    private ModerationProbeMath() {}

    private static final Set<String> APPROVED_STATES = Set.of(
            "ACTIVE", "PAUSED", "ADSET_PAUSED", "CAMPAIGN_PAUSED", "ARCHIVED");
    private static final Set<String> REJECTED_STATES = Set.of("DISAPPROVED", "WITH_ISSUES");
    private static final Set<String> PENDING_STATES = Set.of(
            "PENDING_REVIEW", "IN_PROCESS", "PREAPPROVED", "PENDING_BILLING_INFO");

    /**
     * @param effectiveStatus Meta effective_status (null = unknown)
     * @param hasFeedback     ad_review_feedback present and non-empty (policy violation details)
     */
    public static String verdict(String effectiveStatus, boolean hasFeedback) {
        String st = effectiveStatus == null ? "" : effectiveStatus.trim().toUpperCase(Locale.ROOT);
        if (REJECTED_STATES.contains(st) || (hasFeedback && !PENDING_STATES.contains(st))) return "REJECTED";
        if (APPROVED_STATES.contains(st)) return "APPROVED";
        return "PENDING";
    }

    /** Reasonable rejection code from the feedback payload text (first policy key) or the status. */
    public static String rejectionCode(String effectiveStatus, String feedbackJson) {
        if (feedbackJson != null && !feedbackJson.isBlank() && !"{}".equals(feedbackJson.trim())) {
            String f = feedbackJson.replace("{", "").replace("\"", "").trim();
            int colon = f.indexOf(':');
            String key = colon > 0 ? f.substring(0, colon).trim() : f;
            if (!key.isBlank()) return key.length() > 50 ? key.substring(0, 50) : key;
        }
        return effectiveStatus == null ? "UNKNOWN" : effectiveStatus.toUpperCase(Locale.ROOT);
    }
}
