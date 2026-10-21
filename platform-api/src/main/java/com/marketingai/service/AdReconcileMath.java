package com.marketingai.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure logic of the hourly ad read-back (roadmap Phase 11-4): match a hand-made ad to one of our creatives by
 * the creative code in its name, map Meta's effective_status to our map status, and diff a MANAGED row
 * against what Meta reports (drift).
 */
public final class AdReconcileMath {

    private AdReconcileMath() {}

    private static final Pattern CODE = Pattern.compile("\\b(MAI-[0-9A-F]{6})\\b", Pattern.CASE_INSENSITIVE);

    /** "MAI-7A3F2C" found in an ad name (upper-cased), else null. */
    public static String creativeCodeIn(String adName) {
        if (adName == null) return null;
        Matcher m = CODE.matcher(adName);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** Meta effective_status → map status. */
    public static String statusFor(String effectiveStatus) {
        if (effectiveStatus == null) return "UNKNOWN";
        return switch (effectiveStatus.toUpperCase()) {
            case "ACTIVE" -> "ACTIVE";
            case "PAUSED", "CAMPAIGN_PAUSED", "ADSET_PAUSED" -> "PAUSED";
            case "ARCHIVED", "DELETED" -> "ARCHIVED";
            case "PENDING_REVIEW", "IN_PROCESS", "PREAPPROVED" -> "PENDING_REVIEW";
            case "DISAPPROVED", "WITH_ISSUES" -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    /** creatives.publish_status for a mapped ad in the given map status. */
    public static String publishStatusFor(String mapStatus) {
        return switch (mapStatus) {
            case "ACTIVE" -> "ACTIVE_IN_META";
            case "PAUSED", "APPROVED" -> "PAUSED_IN_META";
            case "REJECTED" -> "REJECTED";
            case "DRIFT" -> "DRIFT";
            case "PENDING_REVIEW" -> "PENDING_REVIEW";
            case "ARCHIVED" -> "NOT_IN_META";
            default -> "UNMANAGED";
        };
    }

    /** Statuses the reconciler must not overwrite from effective_status (moderation flow owns them). */
    public static boolean moderationOwned(String mapStatus) {
        return "PENDING_REVIEW".equals(mapStatus) || "APPROVED".equals(mapStatus) || "REJECTED".equals(mapStatus);
    }

    /**
     * Diff of a MANAGED row against Meta. Keys: link_url, url_tags, video_id, image_hash → {expected, actual}.
     * Empty map = no drift. A field we never set (expected null/blank) is not compared.
     */
    public static Map<String, Map<String, String>> drift(String expectedUrl, String actualUrl,
                                                         String expectedTags, String actualTags,
                                                         String expectedVideo, String actualVideo,
                                                         String expectedImage, String actualImage) {
        Map<String, Map<String, String>> d = new LinkedHashMap<>();
        cmp(d, "link_url", expectedUrl, actualUrl);
        cmp(d, "url_tags", expectedTags, actualTags);
        cmp(d, "video_id", expectedVideo, actualVideo);
        cmp(d, "image_hash", expectedImage, actualImage);
        return d;
    }

    private static void cmp(Map<String, Map<String, String>> d, String field, String expected, String actual) {
        if (expected == null || expected.isBlank()) return;
        String e = norm(expected), a = norm(actual);
        if (!Objects.equals(e, a)) {
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("expected", expected);
            pair.put("actual", actual == null ? "" : actual);
            d.put(field, pair);
        }
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.trim();
        // Meta may return the URL with or without a trailing slash; url_tags with different param order.
        if (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.toLowerCase();
    }
}
