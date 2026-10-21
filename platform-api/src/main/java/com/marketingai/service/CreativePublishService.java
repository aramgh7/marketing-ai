package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.entity.ComplianceCheck;
import com.marketingai.entity.Creative;
import com.marketingai.entity.MetaAdMap;
import com.marketingai.entity.Tenant;
import com.marketingai.entity.TenantDomain;
import com.marketingai.repository.CreativeRepository;
import com.marketingai.repository.LandingVariantRepository;
import com.marketingai.repository.MetaAdMapRepository;
import com.marketingai.repository.TenantDomainRepository;
import com.marketingai.repository.TenantRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap Phase 11-3 (.ai/creative-publishing.md): the only road into Meta goes through the platform.
 * Upload the creative, create the ad creative and the ad PAUSED with the platform's destination URL and
 * url_tags, remember every id in {@code meta_ad_map}, and open a moderation probe row so the verdict is read
 * before anyone activates the ad. Activation (RESUME) and pausing are separate, gated writes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreativePublishService {

    public static final List<String> PUBLISH_STATUSES = List.of(
            "NOT_IN_META", "PENDING_REVIEW", "APPROVED_PAUSED", "ACTIVE_IN_META", "PAUSED_IN_META", "REJECTED", "UNMANAGED", "DRIFT");

    private final CreativeRepository creativeRepo;
    private final TenantRepository tenantRepo;
    private final TenantDomainRepository domainRepo;
    private final LandingVariantRepository variantRepo;
    private final MetaAdMapRepository mapRepo;
    private final ComplianceService complianceService;
    private final MetaWriteGate metaWriteGate;
    private final MetaApiService metaApi;
    private final MetaApiAuditService audit;
    private final TokenVault vault;
    private final AppProperties props;
    private final JdbcTemplate jdbc;

    private final RestTemplate rest = new RestTemplate();

    // ─── preview ──────────────────────────────────────────────────────────────

    public Map<String, Object> preview(UUID creativeId, String entrySlug, String host) {
        UUID tenantId = tenantId();
        Creative c = findCreative(creativeId, tenantId);
        Tenant t = tenant(tenantId);
        String slug = entrySlug == null || entrySlug.isBlank() ? defaultSlug(tenantId) : entrySlug;
        String landingHost = landingHost(tenantId, host);
        List<Map<String, Object>> readiness = readiness(c, t, slug, landingHost);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("creative_id", c.getId());
        out.put("creative_code", c.getCreativeCode());
        out.put("publish_status", c.getPublishStatus());
        out.put("ad_name", CreativePublishMath.adName(c.getCreativeCode(), c.getTitle()));
        out.put("entry_slug", slug);
        out.put("landing_host", landingHost);
        out.put("destination_url", slug == null ? null : safeUrl(landingHost, slug));
        out.put("url_tags", CreativePublishMath.URL_TAGS);
        out.put("readiness", readiness);
        out.put("ready", readiness.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
        out.put("meta_ads", mapRepo.findAllByTenantIdAndCreativeId(tenantId, creativeId).stream().map(this::mapRow).toList());
        return out;
    }

    private List<Map<String, Object>> readiness(Creative c, Tenant t, String slug, String landingHost) {
        List<Map<String, Object>> items = new ArrayList<>();
        String stage = writeStage(t.getId());
        boolean stageOk = !"READ_ONLY".equals(stage);
        items.add(item("stage", "Meta write stage ≥ GUARDED_WRITES", stageOk, "stage " + stage));
        boolean tokenOk = vault.openOrNull(t.getMetaAccessTokenEnc()) != null;
        items.add(item("token", "Meta access token present", tokenOk, tokenOk ? "ok" : "set it in Settings → Meta credentials"));
        boolean acct = t.getMetaAdAccountId() != null && !t.getMetaAdAccountId().isBlank();
        items.add(item("ad_account", "Ad account id", acct, acct ? t.getMetaAdAccountId() : "missing"));
        boolean page = t.getMetaPageId() != null && !t.getMetaPageId().isBlank();
        items.add(item("page_id", "Facebook Page id (object_story_spec)", page, page ? t.getMetaPageId() : "missing — Settings → Meta credentials"));
        boolean file = fileReadable(c);
        items.add(item("file", "Creative file readable", file, c.getFileUrl() == null ? "no file" : c.getFileUrl()));
        boolean compliant = complianceService.isCompliant(c.getId());
        items.add(item("compliance", "Compliance check PASS (or override)", compliant, compliant ? "PASS" : "run /compliance/check for this creative"));
        boolean notArchived = !"ARCHIVED".equals(c.getStatus());
        items.add(item("not_archived", "Creative not archived", notArchived, c.getStatus()));
        boolean slugOk = slug != null && variantRepo.findByTenantIdAndSlugAndStatus(c.getTenantId(), slug, "ACTIVE").isPresent();
        items.add(item("entry_slug", "Entry landing is ACTIVE", slugOk, slug == null ? "no ACTIVE landing yet" : slug));
        boolean hostOk = landingHost != null && !landingHost.isBlank();
        items.add(item("host", "Landing host", hostOk, hostOk ? landingHost : "no verified domain and no cname-target"));
        return items;
    }

    // ─── publish ──────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> publish(UUID creativeId, Map<String, Object> body, UUID userId) {
        UUID tenantId = tenantId();
        Creative c = findCreative(creativeId, tenantId);
        Tenant t = tenant(tenantId);

        String adSetId = str(body.get("meta_adset_id"));
        String slug = str(body.get("entry_slug"));
        String primaryText = str(body.get("primary_text"));
        String headline = str(body.get("headline"));
        String cta = body.get("call_to_action") == null ? "LEARN_MORE" : str(body.get("call_to_action")).toUpperCase();
        String host = landingHost(tenantId, str(body.get("host")));
        if (adSetId == null || slug == null || primaryText == null || headline == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "meta_adset_id, entry_slug, primary_text and headline are required");
        }

        List<Map<String, Object>> readiness = readiness(c, t, slug, host);
        List<Map<String, Object>> failing = readiness.stream().filter(r -> !Boolean.TRUE.equals(r.get("passed"))).toList();
        if (!failing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "not ready to publish: "
                    + failing.stream().map(r -> String.valueOf(r.get("key"))).toList());
        }
        // Ad text goes through the same compliance rulebases as landing text (vision §12.1a; capabilities §7.3).
        ComplianceCheck textCheck = complianceService.runTextCheck("CREATIVE", creativeId, primaryText + "\n" + headline);
        if (!"PASS".equals(textCheck.getResult())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ad text compliance " + textCheck.getResult() + " — edit the text or override the check with a reason");
        }

        String destinationUrl = CreativePublishMath.destinationUrl(host, slug);
        String adName = CreativePublishMath.adName(c.getCreativeCode(), c.getTitle());
        String token = vault.open(t.getMetaAccessTokenEnc());
        String account = t.getMetaAdAccountId();

        // Single choke point for Meta writes (roadmap 9-19): CREATE_AD_PAUSED is allowed from GUARDED_WRITES.
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.CREATE_AD_PAUSED, adSetId));

        long started = System.currentTimeMillis();
        String videoId = null, imageHash = null;
        byte[] bytes = readFile(c);
        String fileName = fileName(c);
        if ("VIDEO".equalsIgnoreCase(c.getType())) {
            videoId = metaApi.uploadVideo(token, account, bytes, fileName);
            auditLog(tenantId, userId, "AD_VIDEO_UPLOAD", "video", videoId, started);
        } else {
            imageHash = metaApi.uploadImage(token, account, bytes, fileName);
            auditLog(tenantId, userId, "AD_IMAGE_UPLOAD", "image", imageHash, started);
        }
        String metaCreativeId = metaApi.createAdCreative(token, account, adName, t.getMetaPageId(), t.getMetaInstagramActorId(),
                videoId, imageHash, c.getThumbnailUrl(), primaryText, headline, destinationUrl, CreativePublishMath.URL_TAGS, cta);
        auditLog(tenantId, userId, "AD_CREATIVE_CREATE", "adcreative", metaCreativeId, started);
        String metaAdId = metaApi.createAd(token, account, adSetId, adName, metaCreativeId);
        auditLog(tenantId, userId, "AD_CREATE_PAUSED", "ad", metaAdId, started);

        MetaAdMap m = new MetaAdMap();
        m.setTenantId(tenantId);
        m.setMetaAdId(metaAdId);
        m.setMetaAdsetId(adSetId);
        m.setMetaCampaignId(campaignOf(tenantId, adSetId));
        m.setMetaCreativeId(metaCreativeId);
        m.setMetaVideoId(videoId);
        m.setMetaImageHash(imageHash);
        m.setCreativeId(creativeId);
        m.setEntrySlug(slug);
        m.setDestinationUrl(destinationUrl);
        m.setUrlTags(CreativePublishMath.URL_TAGS);
        m.setAdName(adName);
        m.setSource("MANAGED");
        m.setStatus("PENDING_REVIEW");
        m.setLastSeenAt(Instant.now());
        mapRepo.save(m);

        c.setPublishStatus("PENDING_REVIEW");
        c.setMetaVideoId(videoId);
        c.setMetaCreativeId(metaCreativeId);
        c.setPublishedAt(Instant.now());
        creativeRepo.save(c);

        // Moderation probe row: ModerationProbeScheduler reads effective_status / ad_review_feedback for this ad id.
        UUID ourAdSet = ourAdSetId(tenantId, adSetId);
        jdbc.update("""
                INSERT INTO moderation_results (tenant_id, creative_id, ad_set_id, probe_type, result, meta_ad_id)
                VALUES (?, ?, ?, 'meta_review', 'PENDING', ?)
                """, tenantId, creativeId, ourAdSet, metaAdId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta_ad_id", metaAdId);
        out.put("meta_creative_id", metaCreativeId);
        out.put("meta_video_id", videoId);
        out.put("meta_image_hash", imageHash);
        out.put("status", "PENDING_REVIEW");
        out.put("ad_name", adName);
        out.put("destination_url", destinationUrl);
        out.put("url_tags", CreativePublishMath.URL_TAGS);
        return out;
    }

    // ─── activate / pause ─────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> activate(UUID creativeId, UUID userId) {
        UUID tenantId = tenantId();
        Creative c = findCreative(creativeId, tenantId);
        MetaAdMap m = latestManaged(tenantId, creativeId);
        if (!"APPROVED".equals(m.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ad " + m.getMetaAdId() + " is " + m.getStatus()
                    + " — only an APPROVED (moderation passed) ad can be activated");
        }
        Tenant t = tenant(tenantId);
        String token = vault.open(t.getMetaAccessTokenEnc());
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.RESUME, m.getMetaAdId()));
        long started = System.currentTimeMillis();
        metaApi.setAdStatus(token, m.getMetaAdId(), "ACTIVE");
        auditLog(tenantId, userId, "AD_ACTIVATE", "ad", m.getMetaAdId(), started);
        m.setStatus("ACTIVE");
        mapRepo.save(m);
        c.setPublishStatus("ACTIVE_IN_META");
        creativeRepo.save(c);
        return Map.of("meta_ad_id", m.getMetaAdId(), "status", "ACTIVE", "publish_status", c.getPublishStatus());
    }

    @Transactional
    public Map<String, Object> pause(UUID creativeId, UUID userId) {
        UUID tenantId = tenantId();
        Creative c = findCreative(creativeId, tenantId);
        MetaAdMap m = latestManaged(tenantId, creativeId);
        Tenant t = tenant(tenantId);
        String token = vault.open(t.getMetaAccessTokenEnc());
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.PAUSE, m.getMetaAdId()));
        long started = System.currentTimeMillis();
        metaApi.setAdStatus(token, m.getMetaAdId(), "PAUSED");
        auditLog(tenantId, userId, "AD_PAUSE", "ad", m.getMetaAdId(), started);
        m.setStatus("PAUSED");
        mapRepo.save(m);
        c.setPublishStatus("PAUSED_IN_META");
        creativeRepo.save(c);
        return Map.of("meta_ad_id", m.getMetaAdId(), "status", "PAUSED", "publish_status", c.getPublishStatus());
    }

    // ─── ad map ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listMap(String status) {
        UUID tenantId = tenantId();
        List<MetaAdMap> rows = status == null || status.isBlank()
                ? mapRepo.findAllByTenantIdOrderByCreatedAtDesc(tenantId)
                : mapRepo.findAllByTenantIdAndStatus(tenantId, status.toUpperCase());
        return rows.stream().map(this::mapRow).toList();
    }

    @Transactional
    public Map<String, Object> link(UUID mapId, UUID creativeId) {
        UUID tenantId = tenantId();
        MetaAdMap m = mapRepo.findByIdAndTenantId(mapId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ad map row not found"));
        Creative c = findCreative(creativeId, tenantId);
        m.setCreativeId(creativeId);
        m.setSource("MANUAL");
        String effective = currentEffectiveStatus(tenantId, m.getMetaAdId());
        boolean active = "ACTIVE".equals(effective);
        m.setStatus(active ? "ACTIVE" : "PAUSED");
        m.setDrift(null);
        m.setDriftDetectedAt(null);
        mapRepo.save(m);
        c.setPublishStatus(active ? "ACTIVE_IN_META" : "PAUSED_IN_META");
        if (c.getMetaCreativeId() == null) c.setMetaCreativeId(m.getMetaCreativeId());
        if (c.getMetaVideoId() == null) c.setMetaVideoId(m.getMetaVideoId());
        creativeRepo.save(c);
        return mapRow(m);
    }

    @Transactional
    public Map<String, Object> acknowledgeDrift(UUID mapId) {
        UUID tenantId = tenantId();
        MetaAdMap m = mapRepo.findByIdAndTenantId(mapId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ad map row not found"));
        Map<String, Object> drift = com.marketingai.util.Jsonb.asMap(m.getDrift());
        // The observed values become the new baseline.
        applyActual(drift, "link_url", v -> m.setDestinationUrl(v));
        applyActual(drift, "url_tags", v -> m.setUrlTags(v));
        applyActual(drift, "video_id", v -> m.setMetaVideoId(v));
        applyActual(drift, "image_hash", v -> m.setMetaImageHash(v));
        m.setDrift(null);
        m.setDriftDetectedAt(null);
        String effective = currentEffectiveStatus(tenantId, m.getMetaAdId());
        boolean active = "ACTIVE".equals(effective);
        m.setStatus(active ? "ACTIVE" : "PAUSED");
        mapRepo.save(m);
        if (m.getCreativeId() != null) {
            creativeRepo.findByIdAndTenantId(m.getCreativeId(), tenantId).ifPresent(c -> {
                c.setPublishStatus(active ? "ACTIVE_IN_META" : "PAUSED_IN_META");
                creativeRepo.save(c);
            });
        }
        return mapRow(m);
    }

    @SuppressWarnings("unchecked")
    private static void applyActual(Map<String, Object> drift, String field, java.util.function.Consumer<String> setter) {
        Object d = drift == null ? null : drift.get(field);
        if (d instanceof Map<?, ?> dm && dm.get("actual") != null) setter.accept(String.valueOf(((Map<String, Object>) dm).get("actual")));
    }

    public Map<String, Object> mapRow(MetaAdMap m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("meta_ad_id", m.getMetaAdId());
        r.put("meta_adset_id", m.getMetaAdsetId());
        r.put("meta_campaign_id", m.getMetaCampaignId());
        r.put("meta_creative_id", m.getMetaCreativeId());
        r.put("ad_name", m.getAdName());
        r.put("creative_id", m.getCreativeId());
        String title = null, code = null;
        if (m.getCreativeId() != null) {
            Creative c = creativeRepo.findByIdAndTenantId(m.getCreativeId(), m.getTenantId()).orElse(null);
            if (c != null) { title = c.getTitle(); code = c.getCreativeCode(); }
        }
        r.put("creative_title", title);
        r.put("creative_code", code);
        r.put("entry_slug", m.getEntrySlug());
        r.put("destination_url", m.getDestinationUrl());
        r.put("url_tags", m.getUrlTags());
        r.put("has_tags", CreativePublishMath.hasTagMarker(m.getUrlTags()));
        r.put("source", m.getSource());
        r.put("status", m.getStatus());
        r.put("drift", m.getDrift() == null ? null : com.marketingai.util.Jsonb.asMap(m.getDrift()));
        r.put("drift_detected_at", m.getDriftDetectedAt());
        r.put("last_seen_at", m.getLastSeenAt());
        r.put("created_at", m.getCreatedAt());
        return r;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private MetaAdMap latestManaged(UUID tenantId, UUID creativeId) {
        return mapRepo.findAllByTenantIdAndCreativeId(tenantId, creativeId).stream()
                .filter(m -> !"ARCHIVED".equals(m.getStatus()))
                .max(java.util.Comparator.comparing(MetaAdMap::getCreatedAt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "creative has no Meta ad — publish it first"));
    }

    private String currentEffectiveStatus(UUID tenantId, String metaAdId) {
        try {
            Tenant t = tenant(tenantId);
            String token = vault.openOrNull(t.getMetaAccessTokenEnc());
            if (token == null) return "UNKNOWN";
            Map<String, String> s = metaApi.getAdReviewStatus(token, metaAdId);
            return s.getOrDefault("effective_status", "UNKNOWN");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /** Verified LANDING domain of the tenant (the explicitly requested one, else the first), else the platform's cname-target. */
    String landingHost(UUID tenantId, String requested) {
        List<TenantDomain> domains = domainRepo.findAllByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .filter(d -> "VERIFIED".equals(d.getStatus()) && "LANDING".equals(d.getKind())).toList();
        if (requested != null && !requested.isBlank()) {
            String want = requested.trim().toLowerCase();
            return domains.stream().map(TenantDomain::getHost).filter(want::equals).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "host " + want + " is not a verified landing domain of this tenant"));
        }
        if (!domains.isEmpty()) return domains.get(0).getHost();
        String fallback = props.getLanding().getCnameTarget();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private String writeStage(UUID tenantId) {
        List<String> rows = jdbc.queryForList("SELECT meta_write_stage FROM tenants WHERE id = ?", String.class, tenantId);
        return rows.isEmpty() || rows.get(0) == null ? "READ_ONLY" : rows.get(0);
    }

    private String defaultSlug(UUID tenantId) {
        return variantRepo.findAllByTenantIdAndStatus(tenantId, "ACTIVE").stream()
                .min(java.util.Comparator.comparing(v -> v.getCreatedAt()))
                .map(v -> v.getSlug()).orElse(null);
    }

    private static String safeUrl(String host, String slug) {
        try { return CreativePublishMath.destinationUrl(host, slug); } catch (IllegalArgumentException e) { return null; }
    }

    private boolean fileReadable(Creative c) {
        try { return readFile(c).length > 0; } catch (Exception e) { return false; }
    }

    private byte[] readFile(Creative c) {
        String url = c.getFileUrl();
        if (url == null || url.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "creative has no file");
        try {
            if (url.startsWith("/files/")) {
                String[] parts = url.substring("/files/".length()).split("/", 3);
                if (parts.length == 3) {
                    Path p = Path.of(props.getStorage().getLocalPath(), parts[0], parts[1], parts[2]);
                    return Files.readAllBytes(p);
                }
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                byte[] b = rest.getForObject(url, byte[].class);
                return b == null ? new byte[0] : b;
            }
            return Files.readAllBytes(Path.of(url));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "cannot read creative file: " + e.getMessage());
        }
    }

    private static String fileName(Creative c) {
        String url = c.getFileUrl();
        if (url == null) return "creative";
        int i = url.lastIndexOf('/');
        return i >= 0 ? url.substring(i + 1) : url;
    }

    private String campaignOf(UUID tenantId, String metaAdSetId) {
        List<String> rows = jdbc.queryForList("""
                SELECT c.meta_campaign_id FROM meta_ad_sets a JOIN meta_campaigns c ON c.id = a.campaign_id
                WHERE a.tenant_id = ? AND a.meta_ad_set_id = ? LIMIT 1
                """, String.class, tenantId, metaAdSetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID ourAdSetId(UUID tenantId, String metaAdSetId) {
        List<UUID> rows = jdbc.queryForList(
                "SELECT id FROM meta_ad_sets WHERE tenant_id = ? AND meta_ad_set_id = ? LIMIT 1", UUID.class, tenantId, metaAdSetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void auditLog(UUID tenantId, UUID userId, String action, String targetType, String targetId, long started) {
        audit.log(tenantId, userId == null ? "USER" : "USER", userId, action, targetType, targetId,
                null, 200, "WRITE", System.currentTimeMillis() - started);
    }

    private Creative findCreative(UUID id, UUID tenantId) {
        return creativeRepo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Creative not found"));
    }

    private Tenant tenant(UUID tenantId) {
        return tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private static UUID tenantId() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> item(String key, String label, boolean passed, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("passed", passed);
        m.put("detail", detail);
        return m;
    }
}
