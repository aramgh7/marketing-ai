package com.marketingai.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * Pure verdict mapping for the moderation probe poll (roadmap 9-8; arch §16.17):
 * Meta {@code effective_status} + {@code ad_review_feedback} → APPROVED / REJECTED / PENDING.
 * <p>
 * Audit M-3: the platform creates ads PAUSED, and Meta may report {@code effective_status=PAUSED} for a paused ad
 * regardless of the review stage — so a non-review status alone is not proof of approval. The verdict is
 * conservative: APPROVED only when the status is not a review / rejection state, the feedback is empty <b>and</b>
 * the ad has existed for at least {@code minAge} ({@code app.meta.moderation-min-age-hours}, default 6 h —
 * Meta's usual review window is 24 h, most verdicts land within a few hours). Until then: PENDING.
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
     * @param age             time since the ad row was created (null = unknown → never APPROVED)
     * @param minAge          minimum age before a quiet status counts as approval
     */
    public static String verdict(String effectiveStatus, boolean hasFeedback, Duration age, Duration minAge) {
        String st = effectiveStatus == null ? "" : effectiveStatus.trim().toUpperCase(Locale.ROOT);
        if (REJECTED_STATES.contains(st) || (hasFeedback && !PENDING_STATES.contains(st))) return "REJECTED";
        if (!APPROVED_STATES.contains(st) || hasFeedback) return "PENDING";
        if (age == null || minAge == null || age.compareTo(minAge) < 0) return "PENDING";
        return "APPROVED";
    }

    /** Age of a row from a JDBC timestamp value (Timestamp / OffsetDateTime / Instant); null when unknown. */
    public static Duration ageOf(Object createdAt, Instant now) {
        Instant t = createdAt instanceof Timestamp ts ? ts.toInstant()
                : createdAt instanceof OffsetDateTime o ? o.toInstant()
                : createdAt instanceof Instant i ? i : null;
        if (t == null) return null;
        return t.isAfter(now) ? Duration.ZERO : Duration.between(t, now);
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
