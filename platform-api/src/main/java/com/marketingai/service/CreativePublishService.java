package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * <p>
 * Audit M-5: {@link #publish} is three steps — a short transaction that records the <i>intent</i> (a PUBLISHING
 * row under an advisory lock on the creative; a second click gets 409), the Meta calls with no transaction open,
 * and a short transaction that records the result. A failure after Meta created something deletes the orphan ad
 * and leaves a PUBLISH_FAILED row with the orphan ids for the reconcile job and the cabinet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreativePublishService {

    public static final List<String> PUBLISH_STATUSES = List.of(
            "NOT_IN_META", "PENDING_REVIEW", "APPROVED_PAUSED", "ACTIVE_IN_META", "PAUSED_IN_META", "REJECTED", "UNMANAGED", "DRIFT");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CreativeRepository creativeRepo;
    private final TenantRepository tenantRepo;
    private final TenantDomainRepository domainRepo;
    private final LandingVariantRepository variantRepo;
    private final MetaAdMapRepository mapRepo;
    private final ComplianceService complianceService;
    private final MetaWriteGate metaWriteGate;
    private final MetaApiService metaApi;
    private final MetaVideoStatusService videoStatus;
    private final MetaApiAuditService audit;
    private final TokenVault vault;
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RestTemplateBuilder restTemplateBuilder;

    /** Downloads of remote creative files (audit M-5в): bounded timeouts, read long enough for a video. */
    private RestTemplate rest;

    @PostConstruct
    void initRest() {
        rest = restTemplateBuilder.connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(300)).build();
    }

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
        // Roadmap 11-3c: a creative rendered by the platform needs a marketer-approved render (video_renders.approved_at).
        long[] renders = renderCounts(c);
        items.add(item("render", "Approved render (platform renders only)", CreativePublishMath.renderReady(renders[0], renders[1]),
                CreativePublishMath.renderDetail(renders[0], renders[1])));
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

    /** Ids Meta handed back during one attempt; filled in order so a failure knows what to compensate. */
    static final class Created {
        String videoId, imageHash, metaCreativeId, metaAdId;
        boolean any() { return videoId != null || metaCreativeId != null || metaAdId != null; }
    }

    /** Audit M-4: the upload succeeded but Meta has not rendered a thumbnail yet — the intent row keeps the video id. */
    static final class VideoProcessing extends ResponseStatusException {
        final String videoId;
        VideoProcessing(String videoId) {
            super(HttpStatus.CONFLICT, "video " + videoId + " is still processing in Meta, retry in a minute");
            this.videoId = videoId;
        }
    }

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

        // 1. Intent (short transaction, advisory lock): a second click / retry sees the PUBLISHING row.
        MetaAdMap intent = tx.execute(s -> recordIntent(tenantId, creativeId, adSetId, slug, destinationUrl, adName));
        String resumedVideoId = intent.getMetaVideoId();

        // 2. Meta calls — no transaction open (a video upload can take minutes).
        Created out = new Created();
        try {
            createInMeta(tenantId, userId, token, account, t, c, adSetId, adName, primaryText, headline, destinationUrl, cta,
                    resumedVideoId, out);
            // 3. Result (short transaction).
            tx.executeWithoutResult(s -> recordResult(tenantId, creativeId, intent.getId(), adSetId, out));
        } catch (VideoProcessing e) {
            checkpointVideo(intent.getId(), e.videoId);
            throw e;
        } catch (RuntimeException e) {
            finishFailed(tenantId, userId, token, creativeId, intent.getId(), out, e);
            throw e;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta_ad_id", out.metaAdId);
        result.put("meta_creative_id", out.metaCreativeId);
        result.put("meta_video_id", out.videoId);
        result.put("meta_image_hash", out.imageHash);
        result.put("status", "PENDING_REVIEW");
        result.put("ad_name", adName);
        result.put("destination_url", destinationUrl);
        result.put("url_tags", CreativePublishMath.URL_TAGS);
        return result;
    }

    /** Runs inside the intent transaction. Returns the PUBLISHING row (new, or the resumed one). */
    private MetaAdMap recordIntent(UUID tenantId, UUID creativeId, String adSetId, String slug, String destinationUrl, String adName) {
        // Serialises concurrent publishes of the same creative for the length of this (short) transaction.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", creativeId.toString());
        Instant now = Instant.now();
        CreativePublishMath.Existing ex = CreativePublishMath.existing(
                mapRepo.findAllByTenantIdAndCreativeId(tenantId, creativeId), adSetId, now);
        switch (ex.state()) {
            case CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT, "creative is already published to ad set "
                    + adSetId + " as Meta ad " + ex.row().getMetaAdId() + " (" + ex.row().getStatus()
                    + ") — pause or archive that ad before publishing again");
            case IN_PROGRESS -> throw new ResponseStatusException(HttpStatus.CONFLICT, "a publish of this creative to ad set "
                    + adSetId + " is already in progress (started " + ex.row().getUpdatedAt() + ")");
            case RESUME -> {
                MetaAdMap m = ex.row();
                m.setDrift(note(CreativePublishMath.STAGE_IN_PROGRESS, null, null));
                return mapRepo.saveAndFlush(m);
            }
            case STALE -> {
                MetaAdMap m = ex.row();
                m.setStatus("PUBLISH_FAILED");
                m.setMetaAdId(CreativePublishMath.failedMarker(creativeId, now));
                m.setDrift(note("PUBLISH_FAILED", "abandoned: no progress for " + CreativePublishMath.PUBLISHING_STALE_AFTER.toMinutes()
                        + " min", orphans(m.getMetaVideoId(), m.getMetaCreativeId(), null, false)));
                mapRepo.saveAndFlush(m);
            }
            case FREE -> { }
        }
        MetaAdMap m = new MetaAdMap();
        m.setTenantId(tenantId);
        m.setMetaAdId(CreativePublishMath.pendingMarker(creativeId));
        m.setMetaAdsetId(adSetId);
        m.setMetaCampaignId(campaignOf(tenantId, adSetId));
        m.setCreativeId(creativeId);
        m.setEntrySlug(slug);
        m.setDestinationUrl(destinationUrl);
        m.setUrlTags(CreativePublishMath.URL_TAGS);
        m.setAdName(adName);
        m.setSource("MANAGED");
        m.setStatus("PUBLISHING");
        m.setDrift(note(CreativePublishMath.STAGE_IN_PROGRESS, null, null));
        return mapRepo.saveAndFlush(m);   // flush: the unique (tenant_id, meta_ad_id) surfaces here, inside the lock
    }

    /** The Meta sequence. No transaction is open here. Every id is written to {@code out} as soon as Meta returns it. */
    private void createInMeta(UUID tenantId, UUID userId, String token, String account, Tenant t, Creative c, String adSetId,
                              String adName, String primaryText, String headline, String destinationUrl, String cta,
                              String resumedVideoId, Created out) {
        // Single choke point for Meta writes (roadmap 9-19): CREATE_AD_PAUSED is allowed from GUARDED_WRITES.
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.CREATE_AD_PAUSED, adSetId));
        long started = System.currentTimeMillis();
        String imageUrl = null;
        if ("VIDEO".equalsIgnoreCase(c.getType())) {
            if (resumedVideoId != null) {
                out.videoId = resumedVideoId;   // uploaded by the previous attempt; Meta was still processing it
            } else {
                out.videoId = metaApi.uploadVideo(token, account, readFile(c), fileName(c));
                auditLog(tenantId, userId, "AD_VIDEO_UPLOAD", "video", out.videoId, started);
            }
            // Audit M-4: video_data needs image_url / image_hash. Meta renders thumbnails asynchronously after the
            // upload; a public https thumbnail of our own is the fallback. Never create a creative without an image.
            imageUrl = videoStatus.awaitThumbnail(token, out.videoId);
            if (imageUrl == null && CreativePublishMath.usableAsImageUrl(c.getThumbnailUrl())) imageUrl = c.getThumbnailUrl();
            if (imageUrl == null) throw new VideoProcessing(out.videoId);
        } else {
            out.imageHash = metaApi.uploadImage(token, account, readFile(c), fileName(c));
            auditLog(tenantId, userId, "AD_IMAGE_UPLOAD", "image", out.imageHash, started);
        }
        out.metaCreativeId = metaApi.createAdCreative(token, account, adName, t.getMetaPageId(), t.getMetaInstagramActorId(),
                out.videoId, out.imageHash, imageUrl, primaryText, headline, destinationUrl, CreativePublishMath.URL_TAGS, cta);
        auditLog(tenantId, userId, "AD_CREATIVE_CREATE", "adcreative", out.metaCreativeId, started);
        out.metaAdId = metaApi.createAd(token, account, adSetId, adName, out.metaCreativeId);
        auditLog(tenantId, userId, "AD_CREATE_PAUSED", "ad", out.metaAdId, started);
    }

    /** Runs inside the result transaction: the intent row becomes the ad row, the creative and the probe row follow. */
    private void recordResult(UUID tenantId, UUID creativeId, UUID intentId, String adSetId, Created out) {
        MetaAdMap m = mapRepo.findById(intentId)
                .orElseThrow(() -> new IllegalStateException("publish intent " + intentId + " vanished"));
        m.setMetaAdId(out.metaAdId);
        m.setMetaCreativeId(out.metaCreativeId);
        m.setMetaVideoId(out.videoId);
        m.setMetaImageHash(out.imageHash);
        m.setStatus("PENDING_REVIEW");
        m.setDrift(null);
        m.setLastSeenAt(Instant.now());
        mapRepo.save(m);

        Creative c = findCreative(creativeId, tenantId);
        c.setPublishStatus("PENDING_REVIEW");
        c.setMetaVideoId(out.videoId);
        c.setMetaCreativeId(out.metaCreativeId);
        c.setPublishedAt(Instant.now());
        creativeRepo.save(c);

        // Moderation probe row: ModerationProbeScheduler reads effective_status / ad_review_feedback for this ad id.
        UUID ourAdSet = ourAdSetId(tenantId, adSetId);
        jdbc.update("""
                INSERT INTO moderation_results (tenant_id, creative_id, ad_set_id, probe_type, result, meta_ad_id)
                VALUES (?, ?, ?, 'meta_review', 'PENDING', ?)
                """, tenantId, creativeId, ourAdSet, out.metaAdId);
    }

    /** Video uploaded, thumbnail not ready: keep the PUBLISHING row with the video id so the retry skips the upload. */
    private void checkpointVideo(UUID intentId, String videoId) {
        try {
            tx.executeWithoutResult(s -> mapRepo.findById(intentId).ifPresent(m -> {
                m.setMetaVideoId(videoId);
                m.setDrift(note(CreativePublishMath.STAGE_VIDEO_PROCESSING, "retry in a minute", null));
                mapRepo.save(m);
            }));
        } catch (Exception e) {
            log.warn("publish checkpoint for intent {} failed: {}", intentId, e.getMessage());
        }
    }

    /**
     * Compensation. The PAUSED ad (if created) is deleted in Meta; the video and the ad creative cannot be deleted
     * through MetaApiService, so they are recorded as orphans on a PUBLISH_FAILED row. Nothing created → the intent
     * row is removed.
     */
    private void finishFailed(UUID tenantId, UUID userId, String token, UUID creativeId, UUID intentId, Created out, Exception cause) {
        boolean adDeleted = false;
        if (out.metaAdId != null) {
            try {
                deleteOrphanAd(tenantId, userId, token, out.metaAdId);
                adDeleted = true;
            } catch (Exception e) {
                log.warn("could not delete orphan Meta ad {} after a failed publish: {}", out.metaAdId, e.getMessage());
            }
        }
        final boolean deleted = adDeleted;
        try {
            tx.executeWithoutResult(s -> mapRepo.findById(intentId).ifPresent(m -> {
                if (!out.any()) { mapRepo.delete(m); return; }
                m.setStatus("PUBLISH_FAILED");
                m.setMetaAdId(out.metaAdId != null && !deleted ? out.metaAdId : CreativePublishMath.failedMarker(creativeId, Instant.now()));
                m.setMetaVideoId(out.videoId);
                m.setMetaCreativeId(out.metaCreativeId);
                m.setMetaImageHash(out.imageHash);
                m.setDrift(note("PUBLISH_FAILED", cause.getMessage(), orphans(out.videoId, out.metaCreativeId, out.metaAdId, deleted)));
                mapRepo.save(m);
            }));
        } catch (Exception e) {
            log.error("publish failure bookkeeping for intent {} failed: {}", intentId, e.getMessage());
        }
        log.warn("publish of creative {} failed after video={} creative={} ad={} (ad deleted: {}): {}",
                creativeId, out.videoId, out.metaCreativeId, out.metaAdId, adDeleted, cause.getMessage());
    }

    private void deleteOrphanAd(UUID tenantId, UUID userId, String token, String metaAdId) {
        // Removing a PAUSED ad that never delivered is protective (the PAUSE class is never refused by the policy).
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.PAUSE, metaAdId));
        long started = System.currentTimeMillis();
        metaApi.setAdStatus(token, metaAdId, "DELETED");
        auditLog(tenantId, userId, "AD_DELETE_ORPHAN", "ad", metaAdId, started);
    }

    /** drift JSON of a PUBLISHING / PUBLISH_FAILED row: {stage, error?, orphans?} — a publish note, not a reconciliation drift. */
    private static String note(String stage, String error, ObjectNode orphans) {
        ObjectNode n = MAPPER.createObjectNode().put("stage", stage);
        if (error != null) n.put("error", error.length() > 500 ? error.substring(0, 500) : error);
        if (orphans != null) n.set("orphans", orphans);
        return n.toString();
    }

    private static ObjectNode orphans(String videoId, String metaCreativeId, String metaAdId, boolean adDeleted) {
        ObjectNode o = MAPPER.createObjectNode();
        if (videoId != null) o.put("video_id", videoId);
        if (metaCreativeId != null) o.put("creative_id", metaCreativeId);
        if (metaAdId != null) { o.put("ad_id", metaAdId); o.put("ad_deleted", adDeleted); }
        return o;
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
        // Audit M-20: the platform creates ad sets PAUSED — an ACTIVE ad inside a paused ad set does not deliver.
        MetaVideoStatusService.DeliveryStatus delivery = videoStatus.adSetDelivery(token, m.getMetaAdsetId());
        String warning = CreativePublishMath.deliveryWarning(delivery.adSetEffectiveStatus(), delivery.campaignEffectiveStatus());
        metaWriteGate.assertAllowed(tenantId, MetaWriteGate.Request.of(MetaWriteAction.RESUME, m.getMetaAdId()));
        long started = System.currentTimeMillis();
        metaApi.setAdStatus(token, m.getMetaAdId(), "ACTIVE");
        auditLog(tenantId, userId, "AD_ACTIVATE", "ad", m.getMetaAdId(), started);
        m.setStatus("ACTIVE");
        mapRepo.save(m);
        c.setPublishStatus(CreativePublishMath.publishStatusAfterActivate(warning));
        creativeRepo.save(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta_ad_id", m.getMetaAdId());
        out.put("status", "ACTIVE");
        out.put("publish_status", c.getPublishStatus());
        out.put("warning", warning);
        out.put("adset_effective_status", delivery.adSetEffectiveStatus());
        out.put("campaign_effective_status", delivery.campaignEffectiveStatus());
        return out;
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

    /** Latest row that owns a real Meta ad: intents (PUBLISHING), failed attempts and archived rows do not count. */
    private MetaAdMap latestManaged(UUID tenantId, UUID creativeId) {
        return mapRepo.findAllByTenantIdAndCreativeId(tenantId, creativeId).stream()
                .filter(m -> !List.of("ARCHIVED", "PUBLISHING", "PUBLISH_FAILED").contains(m.getStatus()))
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

    /** {total, approved} platform renders of the creative (by creative_id or by the render's storage URL). */
    private long[] renderCounts(Creative c) {
        try {
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE approved_at IS NOT NULL AND killed_at IS NULL) AS approved
                    FROM video_renders
                    WHERE tenant_id = ? AND (creative_id = ? OR (storage_url IS NOT NULL AND storage_url = ?))
                    """, c.getTenantId(), c.getId(), c.getFileUrl());
            return new long[]{((Number) r.get("total")).longValue(), ((Number) r.get("approved")).longValue()};
        } catch (Exception e) {
            log.warn("render lookup for creative {} failed: {}", c.getId(), e.getMessage());
            return new long[]{0, 0};
        }
    }

    /** Audit M-31: readiness checks the size, not the content (the file is read once, in publish). */
    private boolean fileReadable(Creative c) {
        try { return fileSize(c) > 0; } catch (Exception e) { return false; }
    }

    private long fileSize(Creative c) throws java.io.IOException {
        String url = c.getFileUrl();
        if (url == null || url.isBlank()) return 0;
        Path local = localPath(url);
        if (local != null) return Files.size(local);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            long len = rest.headForHeaders(url).getContentLength();
            return len < 0 ? 1 : len;   // reachable without Content-Length: treat as present
        }
        return Files.size(Path.of(url));
    }

    private Path localPath(String url) {
        if (url.startsWith("/files/")) {
            String[] parts = url.substring("/files/".length()).split("/", 3);
            if (parts.length == 3) return Path.of(props.getStorage().getLocalPath(), parts[0], parts[1], parts[2]);
        }
        return null;
    }

    private byte[] readFile(Creative c) {
        String url = c.getFileUrl();
        if (url == null || url.isBlank()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "creative has no file");
        try {
            Path local = localPath(url);
            if (local != null) return Files.readAllBytes(local);
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
