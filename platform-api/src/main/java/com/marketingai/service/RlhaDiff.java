package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure diff of landing-variant blocks (roadmap 9-20, vision §13.3): which text slots did a human change?
 * Blocks are a JSON array of objects with an {@code event_id} (or {@code id}) and free-form string slots
 * (headline, subhead, body, cta_text, …). Non-string fields and structural keys are ignored.
 */
public final class RlhaDiff {

    public record SlotChange(String blockEventId, String blockType, String slot, String before, String after) {}

    static final Set<String> IGNORED_KEYS = Set.of("type", "event_id", "id", "block_id", "image_url", "video_url",
            "url", "href", "src", "embed", "calendar_id", "pixel_id");
    static final int MIN_LENGTH = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RlhaDiff() {}

    public static List<SlotChange> changedSlots(String oldBlocksJson, String newBlocksJson) {
        List<SlotChange> out = new ArrayList<>();
        JsonNode oldArr = parse(oldBlocksJson), newArr = parse(newBlocksJson);
        if (oldArr == null || newArr == null || !oldArr.isArray() || !newArr.isArray()) return out;
        for (JsonNode nb : newArr) {
            String id = blockId(nb);
            if (id == null) continue;
            JsonNode ob = null;
            for (JsonNode cand : oldArr) if (id.equals(blockId(cand))) { ob = cand; break; }
            if (ob == null) continue;   // new block: nothing was generated for it
            String type = nb.path("type").asText(ob.path("type").asText(""));
            var fields = nb.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (IGNORED_KEYS.contains(e.getKey()) || !e.getValue().isTextual()) continue;
                JsonNode before = ob.get(e.getKey());
                if (before == null || !before.isTextual()) continue;
                String b = normalise(before.asText()), a = normalise(e.getValue().asText());
                if (b.equals(a) || b.length() < MIN_LENGTH || a.length() < MIN_LENGTH) continue;
                out.add(new SlotChange(id, type, e.getKey(), before.asText().trim(), e.getValue().asText().trim()));
            }
        }
        return out;
    }

    static String blockId(JsonNode b) {
        for (String k : List.of("event_id", "id", "block_id")) {
            if (b.hasNonNull(k) && !b.get(k).asText().isBlank()) return b.get(k).asText();
        }
        return null;
    }

    static String normalise(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    private static JsonNode parse(String json) {
        try { return json == null ? null : MAPPER.readTree(json); } catch (Exception e) { return null; }
    }
}
