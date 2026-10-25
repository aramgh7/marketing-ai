package com.marketingai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.entity.LandingTemplate;
import com.marketingai.repository.LandingTemplateRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final List<String> VALID_LAYOUT_VARIANTS = List.of(
            "centered", "left-text-right-image", "full-bleed-video", "split-screen");

    public static final List<String> VALID_BG_TYPES = List.of("color", "gradient", "image", "video");

    private final LandingTemplateRepository templateRepo;

    // ─── list ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        UUID tenantId = currentTenant();
        return templateRepo.findVisibleToTenant(tenantId).stream()
                .map(this::toMap)
                .toList();
    }

    // ─── create ──────────────────────────────────────────────────────────────

    public Map<String, Object> create(Map<String, Object> body) {
        UUID tenantId = currentTenant();
        LandingTemplate t = buildFromBody(body, tenantId);
        t = templateRepo.save(t);
        log.info("template created: id={} tenant={}", t.getId(), tenantId);
        return toMap(t);
    }

    // ─── update (creates new version; preserves lineage via forked_from) ─────

    public Map<String, Object> update(UUID id, Map<String, Object> body) {
        UUID tenantId = currentTenant();
        LandingTemplate existing = templateRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        LandingTemplate next = buildFromBody(body, tenantId);
        next.setForkedFrom(existing.getForkedFrom() != null ? existing.getForkedFrom() : existing.getId());
        next.setVersion(existing.getVersion() + 1);
        next = templateRepo.save(next);
        log.info("template updated: old={} new={} tenant={}", id, next.getId(), tenantId);
        return toMap(next);
    }

    // ─── fork ────────────────────────────────────────────────────────────────

    public Map<String, Object> fork(UUID sourceId) {
        UUID tenantId = currentTenant();
        LandingTemplate src = templateRepo.findById(sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found"));
        if (src.getTenantId() != null && !src.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot fork another tenant's template");
        }
        LandingTemplate fork = new LandingTemplate();
        fork.setTenantId(tenantId);
        fork.setName(src.getName() + " (copy)");
        fork.setDescription(src.getDescription());
        fork.setVersion(1);
        fork.setBlocks(src.getBlocks());
        fork.setDefaultBgType(src.getDefaultBgType());
        fork.setBackgroundConfig(src.getBackgroundConfig());
        fork.setTags(src.getTags() != null ? Arrays.copyOf(src.getTags(), src.getTags().length) : new String[0]);
        fork.setForkedFrom(src.getId());
        fork = templateRepo.save(fork);
        log.info("template forked: source={} fork={} tenant={}", sourceId, fork.getId(), tenantId);
        return toMap(fork);
    }

    // ─── seed defaults ───────────────────────────────────────────────────────

    @Transactional
    public void seedDefaults() {
        List<LandingTemplate> existing = templateRepo.findPlatformDefaults();
        Set<String> names = new HashSet<>();
        for (LandingTemplate t : existing) names.add(t.getName());
        for (Map<String, Object> def : platformDefaults()) {
            String name = (String) def.get("name");
            if (!names.contains(name)) {
                LandingTemplate t = new LandingTemplate();
                t.setTenantId(null);
                t.setName(name);
                t.setDescription((String) def.get("description"));
                t.setBlocks(json(def.get("blocks")));
                t.setDefaultBgType((String) def.getOrDefault("default_bg_type", "color"));
                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) def.getOrDefault("tags", List.of());
                t.setTags(tags.toArray(new String[0]));
                templateRepo.save(t);
                log.info("seeded platform default template: {}", name);
            }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private LandingTemplate buildFromBody(Map<String, Object> body, UUID tenantId) {
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name required");
        LandingTemplate t = new LandingTemplate();
        t.setTenantId(tenantId);
        t.setName(name.trim());
        t.setDescription(str(body.get("description")));
        Object blocks = body.get("blocks");
        t.setBlocks(blocks == null ? "[]" : json(blocks));
        String bgType = str(body.getOrDefault("default_bg_type", "color"));
        if (!VALID_BG_TYPES.contains(bgType)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid default_bg_type");
        t.setDefaultBgType(bgType);
        Object bgConfig = body.get("background_config");
        t.setBackgroundConfig(bgConfig == null ? null : json(bgConfig));
        @SuppressWarnings("unchecked")
        List<String> tags = body.get("tags") instanceof List<?> l ? (List<String>) l : List.of();
        t.setTags(tags.toArray(new String[0]));
        return t;
    }

    Map<String, Object> toMap(LandingTemplate t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("tenant_id", t.getTenantId());
        m.put("is_platform_default", t.getTenantId() == null);
        m.put("name", t.getName());
        m.put("description", t.getDescription());
        m.put("version", t.getVersion());
        try {
            m.put("blocks", MAPPER.readValue(t.getBlocks() == null ? "[]" : t.getBlocks(),
                    new TypeReference<List<Object>>() {}));
        } catch (Exception e) { m.put("blocks", List.of()); }
        m.put("default_bg_type", t.getDefaultBgType());
        if (t.getBackgroundConfig() != null) {
            try { m.put("background_config", MAPPER.readValue(t.getBackgroundConfig(), Map.class)); }
            catch (Exception e) { m.put("background_config", null); }
        }
        m.put("tags", t.getTags() != null ? Arrays.asList(t.getTags()) : List.of());
        m.put("forked_from", t.getForkedFrom());
        m.put("created_at", t.getCreatedAt());
        m.put("updated_at", t.getUpdatedAt());
        return m;
    }

    static List<Map<String, Object>> platformDefaults() {
        return List.of(
            Map.of("name", "Authority",
                   "description", "Dark premium layout for cold traffic, high-ticket offers",
                   "default_bg_type", "gradient",
                   "tags", List.of("investor", "cold-traffic", "high-ticket"),
                   "blocks", List.of(
                       Map.of("block_type","HERO","position",0,"layout_variant","full-bleed-video","slot_overrides",Map.of()),
                       Map.of("block_type","PROOF","position",1,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","PRICING","position",2,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","CTA","position",3,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","disclaimer_506c","position",4,"layout_variant","centered","slot_overrides",Map.of()))),
            Map.of("name", "Transparency",
                   "description", "Light, data-heavy layout for warm leads",
                   "default_bg_type", "color",
                   "tags", List.of("investor", "warm-leads", "data-first"),
                   "blocks", List.of(
                       Map.of("block_type","HERO","position",0,"layout_variant","left-text-right-image","slot_overrides",Map.of()),
                       Map.of("block_type","data_room","position",1,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","PROBLEM","position",2,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","social_proof_investors","position",3,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","CTA","position",4,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","accreditation_checkbox","position",5,"layout_variant","centered","slot_overrides",Map.of()))),
            Map.of("name", "Story",
                   "description", "Warm founder-forward layout for mid-funnel",
                   "default_bg_type", "color",
                   "tags", List.of("investor", "mid-funnel", "founder-story"),
                   "blocks", List.of(
                       Map.of("block_type","HERO","position",0,"layout_variant","left-text-right-image","slot_overrides",Map.of()),
                       Map.of("block_type","PROBLEM","position",1,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","PROOF","position",2,"layout_variant","split-screen","slot_overrides",Map.of()),
                       Map.of("block_type","materials","position",3,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","CTA","position",4,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","FAQ","position",5,"layout_variant","centered","slot_overrides",Map.of()))),
            Map.of("name", "Fast Track",
                   "description", "Minimal retargeting layout for bottom-of-funnel",
                   "default_bg_type", "gradient",
                   "tags", List.of("investor", "retargeting", "bottom-of-funnel"),
                   "blocks", List.of(
                       Map.of("block_type","HERO","position",0,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","CTA","position",1,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","accreditation_checkbox","position",2,"layout_variant","centered","slot_overrides",Map.of()))),
            Map.of("name", "Education First",
                   "description", "Content-rich layout for education-led cold traffic",
                   "default_bg_type", "color",
                   "tags", List.of("investor", "cold-traffic", "education"),
                   "blocks", List.of(
                       Map.of("block_type","HERO","position",0,"layout_variant","full-bleed-video","slot_overrides",Map.of()),
                       Map.of("block_type","materials","position",1,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","webinar_reg","position",2,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","PROBLEM","position",3,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","PROOF","position",4,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","CTA","position",5,"layout_variant","centered","slot_overrides",Map.of()),
                       Map.of("block_type","disclaimer_506c","position",6,"layout_variant","centered","slot_overrides",Map.of())))
        );
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private String json(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "null"; }
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
