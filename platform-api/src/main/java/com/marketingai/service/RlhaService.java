package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * RLHA loop (roadmap 9-20; vision §13.3): record what the system generated, capture what the human changed,
 * feed the pairs back as few-shot examples, and measure the share of generations accepted without edits.
 *
 *   recordGeneration      ← ContentGenerationController after every /content/generate
 *   captureVariantEdits   ← VariantService.updateVariant (diff of block text slots)
 *   captureBriefEdit      ← BriefService.update (title / product info / notes / audience)
 *   fewShots              → passed to /content/generate as rlha_few_shots
 *   renderFeedback        ← VideoController approve / reject; reasons feed the montage-plan avoid list
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RlhaService {

    public static final int FEW_SHOT_LIMIT = 8;
    public static final List<String> EDIT_REASONS = List.of("TONE", "FACTS", "LEGAL", "LENGTH", "STRUCTURE", "OTHER");
    public static final List<String> REJECT_REASONS = List.of("EDITING", "TEXT", "MUSIC", "FACE", "COMPLIANCE", "PACE", "OTHER");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    // ─── generations ─────────────────────────────────────────────────────────

    public UUID recordGeneration(UUID tenantId, String entityType, UUID entityId, String blockEventId,
                                 String slot, String text, String contentType, String model) {
        if (text == null || text.isBlank()) return null;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO content_generations
                    (id, tenant_id, entity_type, entity_id, block_event_id, slot, generated_text, model_version, content_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, entityType, entityId, blockEventId, slot, text, model, contentType);
        return id;
    }

    /** Latest generation for a slot, if the text that the human edited came from the system. */
    Optional<Map<String, Object>> latestGeneration(UUID tenantId, UUID entityId, String blockEventId, String slot) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, generated_text FROM content_generations
                WHERE tenant_id = ? AND entity_id = ? AND block_event_id IS NOT DISTINCT FROM ? AND slot = ?
                ORDER BY created_at DESC LIMIT 1
                """, tenantId, entityId, blockEventId, slot);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ─── corrections ─────────────────────────────────────────────────────────

    /** Diff old vs new blocks; every changed slot that the system generated becomes a correction pair. */
    public int captureVariantEdits(UUID tenantId, UUID userId, UUID variantId, String oldBlocks, String newBlocks, String reason) {
        int n = 0;
        for (RlhaDiff.SlotChange c : RlhaDiff.changedSlots(oldBlocks, newBlocks)) {
            Optional<Map<String, Object>> gen = latestGeneration(tenantId, variantId, c.blockEventId(), c.slot());
            String generated = gen.map(g -> (String) g.get("generated_text")).orElse(c.before());
            UUID genId = gen.map(g -> (UUID) g.get("id")).orElse(null);
            // A pair is a human preference only if the previous text was system-generated (or an earlier approval);
            // keep the pair either way but mark the source so the few-shot filter can prefer generated ones.
            String context = json(Map.of("content_type", "landing_block", "block_type", c.blockType(), "slot", c.slot(),
                    "source", gen.isPresent() ? "generated" : "previous_text"));
            insertCorrection(tenantId, userId, genId, generated, c.after(), "landing_block", reason, "LANDING_VARIANT", variantId, context);
            n++;
        }
        if (n > 0) log.info("RLHA: {} slot corrections captured for variant {}", n, variantId);
        return n;
    }

    public int captureBriefEdit(UUID tenantId, UUID userId, UUID briefId, Map<String, String> before, Map<String, String> after, String reason) {
        int n = 0;
        for (String field : List.of("title", "targetAudience", "productInfo", "notes")) {
            String b = RlhaDiff.normalise(before.get(field)), a = RlhaDiff.normalise(after.get(field));
            if (b.isEmpty() || a.isEmpty() || b.equals(a) || b.length() < RlhaDiff.MIN_LENGTH) continue;
            insertCorrection(tenantId, userId, null, before.get(field).trim(), after.get(field).trim(), "brief", reason, "BRIEF", briefId,
                    json(Map.of("content_type", "brief", "field", field, "source", "previous_text")));
            n++;
        }
        return n;
    }

    public UUID addManualCorrection(UUID tenantId, UUID userId, String contentType, String generated, String approved, String reason) {
        return insertCorrection(tenantId, userId, null, generated, approved, contentType, reason, null, null,
                json(Map.of("content_type", contentType, "source", "manual")));
    }

    private UUID insertCorrection(UUID tenantId, UUID userId, UUID generationId, String generated, String approved,
                                  String contentType, String reason, String entityType, UUID entityId, String contextJson) {
        UUID id = UUID.randomUUID();
        List<String> tags = classifyDelta(generated, approved);
        String r = reason != null && EDIT_REASONS.contains(reason.toUpperCase()) ? reason.toUpperCase() : null;
        // FACTS edits are not style preferences — keep them out of the few-shot set by default
        boolean fewShot = !"FACTS".equals(r);
        jdbc.update("""
                INSERT INTO rlha_corrections
                    (id, tenant_id, generation_id, generated_text, approved_text, delta_tags, context, use_as_few_shot,
                     edited_by, content_type, edit_reason, entity_type, entity_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, generationId, generated, approved, tags.toArray(new String[0]), contextJson, fewShot,
                userId, contentType, r, entityType, entityId);
        return id;
    }

    /** Best-effort LLM classification of the delta (TONE, LENGTH, FORBIDDEN_WORD, STRUCTURE, BRAND_CONTEXT). */
    @SuppressWarnings("unchecked")
    List<String> classifyDelta(String generated, String approved) {
        try {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            h.set("X-Internal-API-Key", appProperties.getInternalApiKey());
            Map<String, Object> res = restTemplate.postForObject(appProperties.getMlServiceUrl() + "/content/rlha-delta",
                    new HttpEntity<>(Map.of("generated_text", generated, "approved_text", approved), h), Map.class);
            Object tags = res == null ? null : res.get("delta_tags");
            return tags instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    // ─── few-shot ────────────────────────────────────────────────────────────

    public List<Map<String, Object>> fewShots(UUID tenantId, String contentType) {
        return jdbc.queryForList("""
                SELECT generated_text, approved_text, delta_tags
                FROM rlha_corrections
                WHERE tenant_id = ? AND use_as_few_shot = TRUE
                  AND (content_type = ? OR content_type IS NULL)
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, contentType, FEW_SHOT_LIMIT);
    }

    // ─── render feedback ─────────────────────────────────────────────────────

    public void renderFeedback(UUID tenantId, UUID userId, UUID renderId, String decision, String reason, String note) {
        String r = reason != null && REJECT_REASONS.contains(reason.toUpperCase()) ? reason.toUpperCase() : null;
        jdbc.update("""
                INSERT INTO render_feedback (tenant_id, render_id, decision, reason, note, decided_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, tenantId, renderId, decision, r, note, userId);
    }

    /** Avoid-list for the next montage plan: rejection reasons of the last 30 days with counts and notes. */
    public List<String> montageAvoidNotes(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT reason, COUNT(*) AS n, string_agg(DISTINCT LEFT(note, 120), ' | ') AS notes
                FROM render_feedback
                WHERE tenant_id = ? AND decision = 'REJECTED' AND created_at > now() - INTERVAL '30 days'
                GROUP BY reason ORDER BY n DESC LIMIT 6
                """, tenantId);
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            out.add(r.get("reason") + " rejected " + r.get("n") + "x" + (r.get("notes") == null ? "" : ": " + r.get("notes")));
        }
        return out;
    }

    // ─── metrics ─────────────────────────────────────────────────────────────

    /** Share of generations accepted without edits (vision §13.3 target: 30 % → 70 %), overall and by week. */
    public Map<String, Object> metrics(UUID tenantId) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*)                                             AS generations,
                       COUNT(*) FILTER (WHERE edited)                       AS edited,
                       COUNT(*) FILTER (WHERE NOT edited AND approved_after) AS accepted_clean
                FROM (
                    SELECT g.id,
                           EXISTS (SELECT 1 FROM rlha_corrections c WHERE c.generation_id = g.id)        AS edited,
                           EXISTS (SELECT 1 FROM landing_variants lv WHERE lv.id = g.entity_id
                                     AND lv.approved_at IS NOT NULL AND lv.approved_at > g.created_at)   AS approved_after
                    FROM content_generations g
                    WHERE g.tenant_id = ?
                ) x
                """, tenantId);
        List<Map<String, Object>> weekly = jdbc.queryForList("""
                SELECT date_trunc('week', g.created_at)::date::text AS week,
                       COUNT(*) AS generations,
                       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM rlha_corrections c WHERE c.generation_id = g.id)) AS edited
                FROM content_generations g
                WHERE g.tenant_id = ? AND g.created_at > now() - INTERVAL '12 weeks'
                GROUP BY 1 ORDER BY 1
                """, tenantId);
        Map<String, Object> corr = jdbc.queryForMap("""
                SELECT COUNT(*) AS total_corrections,
                       COUNT(*) FILTER (WHERE use_as_few_shot) AS few_shot_count,
                       COUNT(DISTINCT edited_by) AS unique_editors
                FROM rlha_corrections WHERE tenant_id = ?
                """, tenantId);
        Map<String, Object> renders = jdbc.queryForMap("""
                SELECT COUNT(*) FILTER (WHERE decision = 'APPROVED') AS approved,
                       COUNT(*) FILTER (WHERE decision = 'REJECTED') AS rejected
                FROM render_feedback WHERE tenant_id = ?
                """, tenantId);
        long gens = ((Number) totals.get("generations")).longValue();
        long clean = ((Number) totals.get("accepted_clean")).longValue();
        long edited = ((Number) totals.get("edited")).longValue();
        Map<String, Object> out = new LinkedHashMap<>(corr);
        out.put("generations", gens);
        out.put("edited", edited);
        out.put("accepted_without_edits", clean);
        out.put("accepted_without_edits_rate", gens == 0 ? null : Math.round(clean * 1000.0 / gens) / 10.0);
        out.put("edit_rate", gens == 0 ? null : Math.round(edited * 1000.0 / gens) / 10.0);
        out.put("weekly", weekly);
        out.put("renders", renders);
        out.put("target_accepted_rate", 70.0);
        return out;
    }

    private static String json(Map<String, Object> m) {
        try { return MAPPER.writeValueAsString(m); } catch (Exception e) { return "{}"; }
    }
}
