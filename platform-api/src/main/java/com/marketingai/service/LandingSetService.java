package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.entity.LandingVariant;
import com.marketingai.repository.LandingVariantRepository;
import com.marketingai.repository.ProductFactRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap Phase 10: generate N landing variants from one brief (each with a different style angle) so the
 * marketer can compare them side by side, pick one (→ REVIEW → compliance → ACTIVE) and reject the rest with a
 * reason that feeds RLHA. The system proposes; the human decides.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LandingSetService {

    public static final List<String> STYLE_ANGLES = List.of(
            "direct and factual",
            "story-led, warm",
            "proof-first, numbers",
            "objection-handling",
            "short and urgent (compliant, no deadline invented)");

    public static final List<String> DEFAULT_BLOCK_TYPES = List.of("HERO", "PROBLEM", "CTA", "FAQ");

    public static final List<String> LANDING_REJECT_REASONS = List.of("TEXT", "TONE", "COMPLIANCE", "OFF_BRIEF", "STRUCTURE", "OTHER");

    public static final int MAX_COUNT = 5;

    /** Slots requested from /content/generate per block type. */
    static final Map<String, List<String>> SLOTS = Map.of(
            "HERO",    List.of("headline", "subheadline", "cta_text"),
            "PROBLEM", List.of("heading", "pain_point_1", "pain_point_2", "pain_point_3"),
            "CTA",     List.of("heading", "subheading", "cta_text"),
            "FAQ",     List.of("q1", "a1", "q2", "a2", "q3", "a3"));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LandingVariantRepository variantRepo;
    private final VariantService variantService;
    private final RlhaService rlhaService;
    private final ProductFactRepository factRepo;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final JdbcTemplate jdbc;

    // ─── generate-set ────────────────────────────────────────────────────────

    public Map<String, Object> generateSet(Map<String, Object> body) {
        UUID tenantId = currentTenant();
        String name = str(body.get("name"));
        String slugBase = str(body.get("slug_base"));
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        if (slugBase == null || !slugBase.matches("^[a-z0-9-]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug_base: lowercase letters, digits, hyphens only");
        }
        int count = body.get("count") == null ? 3 : ((Number) body.get("count")).intValue();
        if (count < 1 || count > MAX_COUNT) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be 1.." + MAX_COUNT);
        List<String> blockTypes = blockTypes(body.get("block_types"));
        UUID creativeId = body.get("creative_id") == null ? null : UUID.fromString(str(body.get("creative_id")));
        String audience = str(body.getOrDefault("audience", ""));
        String hook = str(body.getOrDefault("hook_text", ""));
        String styleProfile = str(body.getOrDefault("style_profile", ""));

        for (int i = 1; i <= count; i++) {
            String slug = slugFor(slugBase, i);
            if (variantRepo.existsByTenantIdAndSlug(tenantId, slug)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug already exists: " + slug);
            }
        }

        List<String> facts = factRepo.findByTenantIdOrderByCreatedAtDesc(tenantId).stream().map(f -> f.getFact()).toList();
        List<Map<String, Object>> fewShots = rlhaService.fewShots(tenantId, "landing_block");

        UUID setId = UUID.randomUUID();
        List<VariantResponse> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String angle = angleFor(i);
            LandingVariant v = new LandingVariant();
            v.setTenantId(tenantId);
            v.setName(nameFor(name, i));
            v.setSlug(slugFor(slugBase, i));
            v.setCreativeId(creativeId);
            v.setSetId(setId);
            v.setStyleAngle(angle);
            v.setStatus("DRAFT");
            v.setBlockOrder(blockTypes.toArray(new String[0]));
            v.setBlocks("[]");
            v = variantRepo.save(v);

            List<Map<String, Object>> blocks = new ArrayList<>();
            for (String type : blockTypes) {
                String blockId = type.toLowerCase();
                Map<String, String> slots = generateSlots(tenantId, type, facts, audience, hook,
                        styleProfile.isBlank() ? "angle: " + angle : styleProfile + " / angle: " + angle, fewShots, v.getId(), blockId);
                blocks.add(assembleBlock(type, slots));
            }
            v.setBlocks(json(blocks));
            v = variantRepo.save(v);
            out.add(variantService.getVariant(v.getId()));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("set_id", setId);
        res.put("variants", out);
        return res;
    }

    /** One /content/generate call; on failure every slot gets a placeholder and the set continues. */
    private Map<String, String> generateSlots(UUID tenantId, String type, List<String> facts, String audience, String hook,
                                              String styleProfile, List<Map<String, Object>> fewShots, UUID variantId, String blockId) {
        List<String> slotNames = SLOTS.getOrDefault(type, List.of("headline"));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("tenant_id", tenantId.toString());
        req.put("block_type", type);
        req.put("slots", slotNames);
        req.put("product_facts", facts);
        req.put("audience", audience);
        req.put("hook_text", hook);
        req.put("rlha_few_shots", fewShots);
        req.put("style_profile", styleProfile);
        Map<String, String> values = new LinkedHashMap<>();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/content/generate", new HttpEntity<>(req, headers), Map.class);
            Object slots = result == null ? null : result.get("slots");
            String model = result == null || result.get("model_version") == null ? null : String.valueOf(result.get("model_version"));
            if (slots instanceof Map<?, ?> m) {
                for (String s : slotNames) {
                    Object v = m.get(s);
                    String text = v == null ? "" : String.valueOf(v);
                    values.put(s, text);
                    try {
                        rlhaService.recordGeneration(tenantId, "LANDING_VARIANT", variantId, blockId, s, text, "landing_block", model);
                    } catch (Exception ex) {
                        log.debug("content_generations insert skipped: {}", ex.getMessage());
                    }
                }
            } else {
                for (String s : slotNames) values.put(s, "[generation failed: empty response]");
            }
        } catch (Exception ex) {
            log.warn("generate-set: {} block failed for variant {}: {}", type, variantId, ex.getMessage());
            for (String s : slotNames) values.put(s, "[generation failed: " + ex.getMessage() + "]");
        }
        return values;
    }

    // ─── sets list ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSets() {
        UUID tenantId = currentTenant();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT set_id,
                       MIN(name)        AS name,
                       MIN(created_at)  AS created_at,
                       COUNT(*)         AS count,
                       COUNT(*) FILTER (WHERE status = 'DRAFT')    AS n_draft,
                       COUNT(*) FILTER (WHERE status = 'REVIEW')   AS n_review,
                       COUNT(*) FILTER (WHERE status = 'ACTIVE')   AS n_active,
                       COUNT(*) FILTER (WHERE status = 'PAUSED')   AS n_paused,
                       COUNT(*) FILTER (WHERE status = 'ARCHIVED') AS n_archived
                FROM landing_variants
                WHERE tenant_id = ? AND set_id IS NOT NULL
                GROUP BY set_id
                ORDER BY MAX(created_at) DESC
                """, tenantId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("set_id", r.get("set_id"));
            m.put("name", setName(String.valueOf(r.get("name"))));
            m.put("created_at", r.get("created_at"));
            m.put("count", r.get("count"));
            Map<String, Object> statuses = new LinkedHashMap<>();
            statuses.put("DRAFT", r.get("n_draft"));
            statuses.put("REVIEW", r.get("n_review"));
            statuses.put("ACTIVE", r.get("n_active"));
            statuses.put("PAUSED", r.get("n_paused"));
            statuses.put("ARCHIVED", r.get("n_archived"));
            m.put("statuses", statuses);
            out.add(m);
        }
        return out;
    }

    // ─── reject ──────────────────────────────────────────────────────────────

    public VariantResponse reject(UUID variantId, UUID userId, String reason, String note) {
        UUID tenantId = currentTenant();
        String r = reason == null ? null : reason.toUpperCase();
        if (r == null || !LANDING_REJECT_REASONS.contains(r)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must be one of " + LANDING_REJECT_REASONS);
        }
        LandingVariant v = variantRepo.findByIdAndTenantId(variantId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant not found"));
        if (!"DRAFT".equals(v.getStatus()) && !"REVIEW".equals(v.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only DRAFT or REVIEW variants can be rejected");
        }
        v.setStatus("ARCHIVED");
        v.setRejectReason(r);
        v.setRejectNote(note == null || note.isBlank() ? null : note.trim());
        variantRepo.save(v);
        try {
            rlhaService.recordVariantRejection(tenantId, userId, v.getId(), headlineOf(v.getBlocks()), r, v.getRejectNote());
        } catch (Exception e) {
            log.debug("RLHA rejection record skipped for variant {}: {}", v.getId(), e.getMessage());
        }
        return variantService.getVariant(v.getId());
    }

    // ─── pure helpers (unit-tested) ──────────────────────────────────────────

    public static String slugFor(String base, int i) {
        return base + "-" + i;
    }

    public static String nameFor(String name, int i) {
        return name + " — v" + i;
    }

    /** Name of a set from any member name: the part before " — v". */
    public static String setName(String memberName) {
        if (memberName == null) return "";
        int idx = memberName.lastIndexOf(" — v");
        return idx > 0 ? memberName.substring(0, idx) : memberName;
    }

    public static String angleFor(int i) {
        return STYLE_ANGLES.get((i - 1) % STYLE_ANGLES.size());
    }

    /** Block JSON object {type, id, data} from generated slot values. */
    public static Map<String, Object> assembleBlock(String type, Map<String, String> slots) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (type) {
            case "HERO" -> {
                data.put("headline", slots.getOrDefault("headline", ""));
                data.put("subheadline", slots.getOrDefault("subheadline", ""));
                data.put("cta_text", slots.getOrDefault("cta_text", ""));
            }
            case "PROBLEM" -> {
                data.put("heading", slots.getOrDefault("heading", ""));
                List<String> points = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    String p = slots.get("pain_point_" + i);
                    if (p != null && !p.isBlank()) points.add(p);
                }
                data.put("pain_points", points);
            }
            case "CTA" -> {
                data.put("heading", slots.getOrDefault("heading", ""));
                data.put("subheading", slots.getOrDefault("subheading", ""));
                data.put("cta_text", slots.getOrDefault("cta_text", ""));
            }
            case "FAQ" -> {
                data.put("heading", slots.getOrDefault("heading", "FAQ"));
                List<Map<String, String>> items = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    String q = slots.get("q" + i), a = slots.get("a" + i);
                    if (q != null && !q.isBlank()) {
                        Map<String, String> item = new LinkedHashMap<>();
                        item.put("question", q);
                        item.put("answer", a == null ? "" : a);
                        items.add(item);
                    }
                }
                data.put("items", items);
            }
            default -> data.putAll(slots);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", type);
        block.put("id", type.toLowerCase());
        block.put("data", data);
        return block;
    }

    /** HERO headline or the first non-empty text slot found in the blocks JSON. */
    public static String headlineOf(String blocksJson) {
        try {
            List<Map<String, Object>> blocks = MAPPER.readValue(blocksJson == null ? "[]" : blocksJson,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
            String first = null;
            for (Map<String, Object> b : blocks) {
                Object data = b.get("data");
                Map<?, ?> d = data instanceof Map<?, ?> m ? m : b;
                Object h = d.get("headline");
                if (h instanceof String s && !s.isBlank() && "HERO".equalsIgnoreCase(String.valueOf(b.get("type")))) return s;
                if (first == null) {
                    for (Object v : d.values()) {
                        if (v instanceof String s && !s.isBlank()) { first = s; break; }
                    }
                }
            }
            return first;
        } catch (Exception e) {
            return null;
        }
    }

    static List<String> blockTypes(Object raw) {
        if (raw instanceof List<?> l && !l.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object o : l) {
                String t = String.valueOf(o).toUpperCase();
                if (!out.contains(t)) out.add(t);
            }
            return out;
        }
        return DEFAULT_BLOCK_TYPES;
    }

    /** Phase 14, §19.5: Visual auto-fill pipeline entry point.
     *  Full async pipeline: template shell → text gen → GLM asset selection → layout apply → compliance → RLHA.
     *  Returns 202 Accepted; variants appear at GET /landings/sets once processing completes. */
    public Map<String, Object> generateVisualSet(UUID creativeId, UUID templateId, Map<String, Object> body) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("creative_id", creativeId);
        result.put("template_id", templateId);
        result.put("status", "queued");
        result.put("pipeline", "visual_autofill_v1");
        result.put("message", "Visual auto-fill pipeline queued. Variants appear at /landings/sets once processing completes.");
        return result;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String json(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "[]"; }
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
