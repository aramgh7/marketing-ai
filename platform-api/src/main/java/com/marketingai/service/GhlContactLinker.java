package com.marketingai.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure helpers for linking a GHL contact to a first-party session (roadmap 8-9; arch §16.9).
 *
 * Linking order, strongest signal first:
 *   1. our own ids in the contact's custom fields (mai_session_id) — survives sub-account transfers
 *   2. email / phone match against the form submission
 *   3. UTM match (last resort; campaign + 24 h window)
 */
public final class GhlContactLinker {

    private GhlContactLinker() {}

    public enum Method { CUSTOM_FIELD, EMAIL, PHONE, UTM, NONE }

    /**
     * GHL workflow webhooks carry custom values either as {@code customData: {key: value}} or as
     * {@code customFields: [{key|id|name, value|field_value}]}; either shape is accepted.
     */
    @SuppressWarnings("unchecked")
    public static Optional<String> extractCustomField(Map<String, Object> raw, String key) {
        if (raw == null || key == null) return Optional.empty();
        Object direct = raw.get(key);
        if (direct != null && !direct.toString().isBlank()) return Optional.of(direct.toString().trim());

        Object customData = raw.get("customData");
        if (customData instanceof Map<?, ?> cd) {
            Object v = ((Map<String, Object>) cd).get(key);
            if (v != null && !v.toString().isBlank()) return Optional.of(v.toString().trim());
        }
        Object customFields = raw.get("customFields");
        if (customFields instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> f = (Map<String, Object>) m;
                String k = firstNonNull(f.get("key"), f.get("name"), f.get("id"));
                if (k != null && (k.equalsIgnoreCase(key) || k.equalsIgnoreCase(key.replace('_', ' ')))) {
                    String v = firstNonNull(f.get("value"), f.get("field_value"), f.get("fieldValue"));
                    if (v != null && !v.isBlank()) return Optional.of(v.trim());
                }
            }
        } else if (customFields instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get(key);
            if (v != null && !v.toString().isBlank()) return Optional.of(v.toString().trim());
        }
        return Optional.empty();
    }

    /** Digits only, leading country code kept; null for anything shorter than 7 digits. */
    public static String normalisePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        return digits.length() >= 7 ? digits : null;
    }

    private static String firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v.toString();
        return null;
    }
}
