package com.marketingai.service;

/**
 * Pure helpers for roadmap Phase 11 publishing: destination URL, url_tags and the Meta ad name.
 * The ad name carries the creative code so a hand-made copy of the ad can still be matched (11-4).
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
}
