package com.marketingai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.repository.LandingTemplateRepository;
import com.marketingai.repository.MediaAssetRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 14, §19.5: Auto-fill pipeline.
 * Template + product facts + asset library → assembled landing variant scaffold.
 *
 * Pipeline:
 *   1. Load template blocks (layout_variant, slot_overrides)
 *   2. For each block, select the best asset per slot using L2 tag filter + GLM visual atom ranking
 *   3. Return assembled block list ready for LandingVariant creation
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AutoFillService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LandingTemplateRepository templateRepo;
    private final MediaAssetRepository assetRepo;

    /** L2 tag required per logical slot name. */
    private static final Map<String, String> SLOT_L2_MAP = Map.of(
            "hero",       "hero_background",
            "hero_face",  "hero_face",
            "proof",      "proof_visual",
            "social",     "social_proof_avatar",
            "materials",  "materials_thumbnail",
            "ad_primary", "ad_creative_primary"
    );

    /** L2 tag that is the canonical asset slot for each block type (first match wins). */
    private static final Map<String, String> BLOCK_TYPE_L2 = Map.of(
            "HERO",                    "hero_background",
            "PROOF",                   "proof_visual",
            "social_proof_investors",  "social_proof_avatar",
            "materials",               "materials_thumbnail"
    );

    /**
     * Assemble a landing variant scaffold from a template.
     *
     * @param templateId  UUID of the template to fill
     * @param facts       product facts map (headline, subheadline, …) — text slots, not validated here
     * @param visualAtoms list of {slot, l1_tag, coefficient} from /glm/visual-atoms (may be empty)
     * @return assembled block list — each block has layout_variant, data (text facts), and asset_id where selected
     */
    public List<Map<String, Object>> assemble(
            UUID templateId,
            Map<String, Object> facts,
            List<Map<String, Object>> visualAtoms
    ) {
        UUID tenantId = currentTenant();

        var template = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));

        // Platform defaults (tenantId == null) are visible to all tenants; tenant templates must match
        if (template.getTenantId() != null && !template.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Template not accessible");
        }

        List<Map<String, Object>> templateBlocks = parseBlocks(template.getBlocks());
        List<Map<String, Object>> tenantAssets = loadAssetsAsMap(tenantId);

        List<Map<String, Object>> assembled = new ArrayList<>();
        for (Map<String, Object> tBlock : templateBlocks) {
            assembled.add(fillBlock(tBlock, facts, tenantAssets, visualAtoms, tenantId));
        }

        log.info("auto-fill: template={} tenant={} blocks={}", templateId, tenantId, assembled.size());
        return assembled;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> fillBlock(
            Map<String, Object> tBlock,
            Map<String, Object> facts,
            List<Map<String, Object>> assets,
            List<Map<String, Object>> visualAtoms,
            UUID tenantId
    ) {
        String blockType = str(tBlock.get("block_type"));
        String layoutVariant = str(tBlock.getOrDefault("layout_variant", "centered"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("block_type", blockType);
        result.put("position", tBlock.get("position"));
        result.put("layout_variant", layoutVariant);

        // Apply text facts
        Map<String, Object> data = new LinkedHashMap<>();
        if (facts != null) data.putAll(facts);

        // Select asset for this block type
        String requiredL2 = BLOCK_TYPE_L2.get(blockType);
        if (requiredL2 != null) {
            String slotPosition = l2ToSlotPosition(requiredL2);
            Map<String, Object> bestAsset = selectBestAsset(slotPosition, assets, visualAtoms, requiredL2);
            if (bestAsset != null) {
                data.put("asset_id", bestAsset.get("id"));
                data.put("asset_s3_key", bestAsset.get("s3_key"));
                data.put("asset_mime_type", bestAsset.get("mime_type"));
            }
        }

        result.put("data", data);
        return result;
    }

    private Map<String, Object> selectBestAsset(
            String slotPosition,
            List<Map<String, Object>> assets,
            List<Map<String, Object>> visualAtoms,
            String requiredL2
    ) {
        // Filter to assets with required L2 tag
        List<Map<String, Object>> candidates = assets.stream()
                .filter(a -> {
                    @SuppressWarnings("unchecked")
                    List<String> l2 = (List<String>) a.getOrDefault("tags_l2", List.of());
                    return l2.contains(requiredL2);
                })
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return null;

        // Build coefficient map for this slot
        Map<String, Double> coefMap = new HashMap<>();
        for (Map<String, Object> atom : visualAtoms) {
            String atomSlot = str(atom.get("slot"));
            String expectedSlot = slotPosition + "_l1";
            if (expectedSlot.equals(atomSlot)) {
                String l1Tag = str(atom.get("l1_tag"));
                Object coef = atom.get("coefficient");
                if (l1Tag != null && coef instanceof Number n) {
                    coefMap.put(l1Tag, n.doubleValue());
                }
            }
        }

        // Rank by coefficient; tie-break by asset created_at (stable ordering)
        return candidates.stream()
                .max(Comparator.comparingDouble(a -> {
                    @SuppressWarnings("unchecked")
                    List<String> l1 = (List<String>) a.getOrDefault("tags_l1", List.of());
                    String l1Tag = l1.isEmpty() ? "none" : l1.get(0);
                    return coefMap.getOrDefault(l1Tag, 0.0);
                }))
                .orElse(candidates.get(0));
    }

    private List<Map<String, Object>> loadAssetsAsMap(UUID tenantId) {
        return assetRepo.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("s3_key", a.getS3Key());
                    m.put("mime_type", a.getMimeType());
                    m.put("tags_l1", a.getTagsL1() != null ? Arrays.asList(a.getTagsL1()) : List.of());
                    m.put("tags_l2", a.getTagsL2() != null ? Arrays.asList(a.getTagsL2()) : List.of());
                    return m;
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseBlocks(String blocksJson) {
        if (blocksJson == null || blocksJson.isBlank()) return List.of();
        try {
            return MAPPER.readValue(blocksJson, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String l2ToSlotPosition(String l2) {
        // hero_background → hero, proof_visual → proof, etc.
        for (Map.Entry<String, String> e : SLOT_L2_MAP.entrySet()) {
            if (e.getValue().equals(l2)) return e.getKey();
        }
        return l2.split("_")[0]; // fallback: first segment
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
