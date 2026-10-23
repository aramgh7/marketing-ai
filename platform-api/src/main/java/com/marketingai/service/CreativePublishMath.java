package com.marketingai.service;

import com.marketingai.entity.MetaAdMap;
import com.marketingai.util.Jsonb;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pure helpers for roadmap Phase 11 publishing: destination URL, url_tags and the Meta ad name.
 * The ad name carries the creative code so a hand-made copy of the ad can still be matched (11-4).
 * Audit M-4 / M-5 / M-20: thumbnail choice, publish intent bookkeeping and the ad set delivery warning.
 */
public final class CreativePublishMath {

    private CreativePublishMath() {}

    /** url_tags Meta appends to the destination URL at click time; mai_c is the ad id → creative map key. */
    public static final String URL_TAGS =
            "mai_c={{ad.id}}&mai_p={{placement}}&mai_s={{site_source_name}}"
            + "&utm_source=meta&utm_medium=paid&utm_campaign={{campaign.name}}";

    /** https://{host}/l/{slug}; host falls back to the platform's landing host when the tenant has no verified domain. */
    public static String destinationUrl(String host, String entrySlug) {
        if (entrySlug == null || entrySlug.isBlank()) throw new IllegalArgumentException("entry_slug required");
        if (!entrySlug.matches("^[a-z0-9-]+$")) throw new IllegalArgumentException("entry_slug: lowercase letters, digits, hyphens only");
        String h = host == null ? "" : host.trim().toLowerCase();
        if (h.isEmpty()) throw new IllegalArgumentException("landing host required");
        h = h.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        return "https://" + h + "/l/" + entrySlug;
    }

    /** "{code} · {title}" truncated to Meta's 255-char limit; title may be null. */
    public static String adName(String creativeCode, String title) {
        String code = creativeCode == null || creativeCode.isBlank() ? "MAI" : creativeCode.trim();
        String t = title == null || title.isBlank() ? "creative" : title.trim();
        String name = code + " · " + t;
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    /** The url_tags string carries the platform's creative marker. */
    public static boolean hasTagMarker(String urlTags) {
        return urlTags != null && urlTags.contains("mai_c=");
    }

    // ─── Audit M-4: video thumbnail ───────────────────────────────────────────

    /** Preferred thumbnail URI from {@code GET /{video_id}/thumbnails} rows ({uri, is_preferred}); first one otherwise; null when none. */
    public static String preferredThumbnail(List<Map<String, Object>> thumbnails) {
        if (thumbnails == null) return null;
        String first = null;
        for (Map<String, Object> t : thumbnails) {
            Object uri = t.get("uri");
            if (uri == null || String.valueOf(uri).isBlank()) continue;
            if (Boolean.TRUE.equals(t.get("is_preferred"))) return String.valueOf(uri);
            if (first == null) first = String.valueOf(uri);
        }
        return first;
    }

    /** Only a public https URL can serve as Meta's {@code image_url}; local /files/… paths cannot. */
    public static boolean usableAsImageUrl(String thumbnailUrl) {
        return thumbnailUrl != null && thumbnailUrl.startsWith("https://");
    }

    // ─── Audit M-5: publish intent (meta_ad_map row written before the Meta calls) ───

    /** meta_ad_id placeholder of a PUBLISHING row (column is NOT NULL and unique per tenant). */
    public static String pendingMarker(UUID creativeId) { return "pending:" + creativeId; }

    /** meta_ad_id of a PUBLISH_FAILED row that owns no ad (unique so the next retry can insert a fresh intent). */
    public static String failedMarker(UUID creativeId, Instant at) { return "failed:" + creativeId + ":" + at.toEpochMilli(); }

    public static boolean isMarker(String metaAdId) {
        return metaAdId == null || metaAdId.startsWith("pending:") || metaAdId.startsWith("failed:");
    }

    public static final String STAGE_IN_PROGRESS = "IN_PROGRESS";
    public static final String STAGE_VIDEO_PROCESSING = "VIDEO_PROCESSING";
    /** A PUBLISHING row nobody touched for this long is an abandoned attempt (process died mid-upload). */
    public static final Duration PUBLISHING_STALE_AFTER = Duration.ofMinutes(30);

    public enum PublishState {
        /** no live row for this creative + ad set */
        FREE,
        /** an ad already exists (or may exist) in Meta → 409 */
        CONFLICT,
        /** another request is running the Meta calls right now → 409 */
        IN_PROGRESS,
        /** a previous attempt stopped at "video still processing": reuse its video id */
        RESUME,
        /** a PUBLISHING row older than PUBLISHING_STALE_AFTER: mark failed, start over */
        STALE
    }

    public record Existing(PublishState state, MetaAdMap row) {}

    /**
     * What an existing map row of (creative, ad set) means for a new publish. ARCHIVED / REJECTED rows and
     * PUBLISH_FAILED rows that own no real ad id never block.
     */
    public static Existing existing(List<MetaAdMap> rows, String adSetId, Instant now) {
        if (rows == null || adSetId == null) return new Existing(PublishState.FREE, null);
        for (MetaAdMap m : rows) {
            if (!adSetId.equals(m.getMetaAdsetId())) continue;
            String st = m.getStatus() == null ? "" : m.getStatus();
            switch (st) {
                case "ARCHIVED", "REJECTED" -> { }
                case "PUBLISH_FAILED" -> { if (!isMarker(m.getMetaAdId())) return new Existing(PublishState.CONFLICT, m); }
                case "PUBLISHING" -> {
                    if (STAGE_VIDEO_PROCESSING.equals(stage(m.getDrift()))) return new Existing(PublishState.RESUME, m);
                    Instant touched = m.getUpdatedAt() != null ? m.getUpdatedAt() : m.getCreatedAt();
                    boolean stale = touched == null || touched.plus(PUBLISHING_STALE_AFTER).isBefore(now);
                    return new Existing(stale ? PublishState.STALE : PublishState.IN_PROGRESS, m);
                }
                default -> { return new Existing(PublishState.CONFLICT, m); }
            }
        }
        return new Existing(PublishState.FREE, null);
    }

    /** {@code drift.stage} of a PUBLISHING / PUBLISH_FAILED row (the column doubles as the publish note there). */
    public static String stage(Object driftJson) {
        Object s = Jsonb.asMap(driftJson).get("stage");
        return s == null ? null : String.valueOf(s);
    }

    // ─── Audit M-20: delivery status before RESUME ───────────────────────────

    public static final String ADSET_PAUSED_WARNING = "ad set is paused; the ad will not deliver until the ad set is resumed";
    public static final String CAMPAIGN_PAUSED_WARNING = "campaign is paused; the ad will not deliver until the campaign is resumed";

    /**
     * Warning for the activate response when the ad set or its campaign is not delivering; null when both are
     * ACTIVE or unknown (a failed read must not block a human's RESUME — the reconcile job reports the truth later).
     */
    public static String deliveryWarning(String adSetEffectiveStatus, String campaignEffectiveStatus) {
        String campaign = norm(campaignEffectiveStatus);
        if (campaign != null && !"ACTIVE".equals(campaign)) {
            return "PAUSED".equals(campaign) ? CAMPAIGN_PAUSED_WARNING
                    : "campaign is " + campaign + "; the ad will not deliver until the campaign is active";
        }
        String adSet = norm(adSetEffectiveStatus);
        if (adSet != null && !"ACTIVE".equals(adSet)) {
            return "PAUSED".equals(adSet) || "CAMPAIGN_PAUSED".equals(adSet) ? ADSET_PAUSED_WARNING
                    : "ad set is " + adSet + "; the ad will not deliver until the ad set is active";
        }
        return null;
    }

    /** creatives.publish_status after a RESUME: ACTIVE_IN_META only when the ad can actually deliver. */
    public static String publishStatusAfterActivate(String warning) {
        return warning == null ? "ACTIVE_IN_META" : "APPROVED_PAUSED";
    }

    // ─── Roadmap 11-3c: approved render ───────────────────────────────────────

    /** A creative with platform renders needs an approved, not killed one; an uploaded file has no render to approve. */
    public static boolean renderReady(long renders, long approvedRenders) {
        return renders == 0 || approvedRenders > 0;
    }

    public static String renderDetail(long renders, long approvedRenders) {
        if (renders == 0) return "uploaded file — no platform render to approve";
        return approvedRenders > 0 ? approvedRenders + " approved of " + renders
                : renders + " render(s), none approved — approve one on the video page";
    }

    private static String norm(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
