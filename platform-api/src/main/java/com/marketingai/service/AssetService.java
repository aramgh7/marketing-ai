package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.entity.MediaAsset;
import com.marketingai.repository.MediaAssetRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AssetService {

    public static final List<String> L1_VALUES = List.of(
            "founder_portrait","team_photo","property_aerial","property_interior",
            "chart_graph","document","abstract_visual","product_shot");

    public static final List<String> L2_VALUES = List.of(
            "hero_background","hero_face","proof_visual","social_proof_avatar",
            "materials_thumbnail","ad_creative_primary","ad_creative_secondary");

    public static final List<String> L3_VALUES = List.of(
            "professional","warm_approachable","aspirational",
            "technical_data_driven","urgent","calm_trustworthy");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MediaAssetRepository assetRepo;
    private final JdbcTemplate jdbc;

    // ─── register (called after client uploads to S3) ────────────────────────

    public Map<String, Object> register(Map<String, Object> body) {
        UUID tenantId = currentTenant();
        String s3Key = str(body.get("s3_key"));
        String mimeType = str(body.get("mime_type"));
        if (s3Key == null || s3Key.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "s3_key required");
        if (mimeType == null || mimeType.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mime_type required");

        MediaAsset a = new MediaAsset();
        a.setTenantId(tenantId);
        a.setS3Key(s3Key);
        a.setMimeType(mimeType);
        a.setWidth(intVal(body.get("width")));
        a.setHeight(intVal(body.get("height")));
        a.setDurationS(dblVal(body.get("duration_s")));
        a = assetRepo.save(a);
        log.info("asset registered: id={} tenant={}", a.getId(), tenantId);
        return toMap(a);
    }

    // ─── list (with optional tag filters) ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String l1Filter, String l2Filter, String l3Filter) {
        UUID tenantId = currentTenant();
        List<MediaAsset> assets;
        if (l2Filter != null && !l2Filter.isBlank()) {
            assets = assetRepo.findByTenantIdAndTagsL2Contains(tenantId, l2Filter);
        } else {
            assets = assetRepo.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return assets.stream()
                .filter(a -> l1Filter == null || Arrays.asList(a.getTagsL1()).contains(l1Filter))
                .filter(a -> l3Filter == null || Arrays.asList(a.getTagsL3()).contains(l3Filter))
                .map(this::toMap)
                .toList();
    }

    // ─── save confirmed tags + log RLHA diff ─────────────────────────────────

    public Map<String, Object> saveTags(UUID assetId, Map<String, Object> body) {
        UUID tenantId = currentTenant();
        MediaAsset a = assetRepo.findByIdAndTenantId(assetId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));

        String[] l1 = toArray(body.get("tags_l1"));
        String[] l2 = toArray(body.get("tags_l2"));
        String[] l3 = toArray(body.get("tags_l3"));

        // Read suggestions before overwriting for RLHA diff
        Map<String, Object> suggested = parseSuggested(a.getAutoTagSuggested());

        a.setTagsL1(l1);
        a.setTagsL2(l2);
        a.setTagsL3(l3);
        a.setTagConfirmedAt(Instant.now());
        a = assetRepo.save(a);

        logTagRlha(a.getId(), "L1", arrFromMap(suggested, "tags_l1"), l1, tenantId);
        logTagRlha(a.getId(), "L2", arrFromMap(suggested, "tags_l2"), l2, tenantId);
        logTagRlha(a.getId(), "L3", arrFromMap(suggested, "tags_l3"), l3, tenantId);

        return toMap(a);
    }

    // ─── store Claude Vision auto-tag suggestions ─────────────────────────────

    public Map<String, Object> storeAutoTagSuggestions(UUID assetId, Map<String, Object> suggestions) {
        UUID tenantId = currentTenant();
        MediaAsset a = assetRepo.findByIdAndTenantId(assetId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        a.setAutoTagSuggested(json(suggestions));
        assetRepo.save(a);
        return toMap(a);
    }

    // ─── placement history ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> placements(UUID assetId) {
        UUID tenantId = currentTenant();
        assetRepo.findByIdAndTenantId(assetId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
        return jdbc.queryForList(
                "SELECT id, context_type, context_id, slot_name, placed_at FROM asset_placements WHERE asset_id = ? ORDER BY placed_at DESC",
                assetId);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    Map<String, Object> toMap(MediaAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("tenant_id", a.getTenantId());
        m.put("s3_key", a.getS3Key());
        m.put("mime_type", a.getMimeType());
        m.put("width", a.getWidth());
        m.put("height", a.getHeight());
        m.put("duration_s", a.getDurationS());
        m.put("tags_l1", a.getTagsL1() != null ? Arrays.asList(a.getTagsL1()) : List.of());
        m.put("tags_l2", a.getTagsL2() != null ? Arrays.asList(a.getTagsL2()) : List.of());
        m.put("tags_l3", a.getTagsL3() != null ? Arrays.asList(a.getTagsL3()) : List.of());
        m.put("tag_confirmed_at", a.getTagConfirmedAt());
        m.put("created_at", a.getCreatedAt());
        if (a.getAutoTagSuggested() != null) {
            try { m.put("auto_tag_suggested", MAPPER.readValue(a.getAutoTagSuggested(), Map.class)); }
            catch (Exception e) { /* skip malformed JSON */ }
        }
        return m;
    }

    private void logTagRlha(UUID assetId, String level, String[] suggested, String[] confirmed, UUID userId) {
        try {
            boolean corrected = !Arrays.equals(suggested, confirmed);
            // Cast via SQL literal: ARRAY[...] syntax avoids needing PGobject on the classpath
            String sql = "INSERT INTO tag_rlha_log(asset_id,tag_level,suggested_tags,confirmed_tags,was_corrected,corrected_by) "
                    + "VALUES(?::uuid,?,?::text[],?::text[],?,?::uuid)";
            jdbc.update(sql, assetId.toString(), level,
                    pgArray(suggested), pgArray(confirmed),
                    corrected, corrected && userId != null ? userId.toString() : null);
        } catch (Exception e) {
            log.debug("tag_rlha_log insert skipped: {}", e.getMessage());
        }
    }

    private String pgArray(String[] arr) {
        if (arr == null || arr.length == 0) return "{}";
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append('"').append(arr[i].replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSuggested(String json) {
        if (json == null) return Map.of();
        try { return MAPPER.readValue(json, Map.class); } catch (Exception e) { return Map.of(); }
    }

    private String[] arrFromMap(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> l) return l.stream().map(Object::toString).toArray(String[]::new);
        return new String[0];
    }

    private String[] toArray(Object raw) {
        if (raw instanceof List<?> l) return l.stream().map(Object::toString).toArray(String[]::new);
        return new String[0];
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Integer intVal(Object o) { return o instanceof Number n ? n.intValue() : null; }
    private Double dblVal(Object o) { return o instanceof Number n ? n.doubleValue() : null; }
    private String json(Object o) { try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "{}"; } }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
