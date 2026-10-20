package com.marketingai.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * JdbcTemplate returns {@code jsonb} columns as {@code org.postgresql.util.PGobject}, never as String
 * or Map. The driver is a runtime-only dependency, so the type is matched by name.
 * Audit §5: three code paths cast them directly (ClassCastException, or {@code instanceof Map}
 * that is never true), so the weekly competitive-intelligence scan never ran, cluster centroids
 * were always empty and funnel news tags were always empty.
 */
public final class Jsonb {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Jsonb() {}

    /** Raw JSON text of a jsonb column value (PGobject, String) or null. */
    public static String asString(Object value) {
        if (value == null) return null;
        if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            return value.toString();  // PGobject.toString() returns the raw JSON value
        }
        if (value instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** jsonb object as a Map; empty map when null, not an object, or unparsable. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        String json = asString(value);
        if (json == null || json.isBlank()) return Map.of();
        try {
            Object parsed = MAPPER.readValue(json, Object.class);
            return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** jsonb array as a List; empty list when null, not an array, or unparsable. */
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> l) {
            @SuppressWarnings("unchecked") List<Object> cast = (List<Object>) l;
            return cast;
        }
        String json = asString(value);
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
