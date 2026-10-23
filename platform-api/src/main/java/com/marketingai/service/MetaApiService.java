package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Low-level wrapper over Meta Graph API v20.0.
 * Uses RestTemplate (synchronous) — rate-limit retries are caller's responsibility.
 * Docs: https://developers.facebook.com/docs/marketing-api/reference
 */
@Slf4j
@Service
public class MetaApiService {

    private static final String BASE_URL = "https://graph.facebook.com/v20.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = new RestTemplate();

    // ─── Campaigns ──────────────────────────────────────────────────────────

    /**
     * Create a campaign in Meta Ads Manager.
     * Returns Meta's campaign ID (e.g. "120210000000000").
     */
    public String createCampaign(String accessToken, String adAccountId,
                                 String name, String objective, Float dailyBudget) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("name", name)
                .put("objective", objective)
                .put("status", "PAUSED");
        body.putArray("special_ad_categories");

        if (dailyBudget != null) {
            body.put("daily_budget", Math.round(dailyBudget * 100)); // cents
        }

        return postAndGetId(accessToken, "/" + adAccountId + "/campaigns", body);
    }

    /**
     * Set campaign status: ACTIVE, PAUSED, ARCHIVED, DELETED.
     */
    public void setCampaignStatus(String accessToken, String metaCampaignId, String status) {
        ObjectNode body = MAPPER.createObjectNode().put("status", status);
        post(accessToken, "/" + metaCampaignId, body);
        log.info("Meta campaign {} → {}", metaCampaignId, status);
    }

    // ─── Ad Sets ────────────────────────────────────────────────────────────

    /**
     * Create an ad set under the given campaign.
     * Returns Meta's ad set ID.
     */
    public String createAdSet(String accessToken, String adAccountId,
                              String metaCampaignId, String name,
                              String audienceSpec, Float dailyBudget) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("name", name)
                .put("campaign_id", metaCampaignId)
                .put("billing_event", "IMPRESSIONS")
                .put("optimization_goal", "REACH")
                .put("status", "PAUSED");

        if (dailyBudget != null) {
            body.put("daily_budget", Math.round(dailyBudget * 100));
        }

        if (audienceSpec != null) {
            try {
                JsonNode spec = MAPPER.readTree(audienceSpec);
                body.set("targeting", spec);
            } catch (Exception e) {
                log.warn("Invalid audienceSpec JSON, skipping targeting: {}", e.getMessage());
            }
        }

        return postAndGetId(accessToken, "/" + adAccountId + "/adsets", body);
    }

    /**
     * Set ad set status: ACTIVE, PAUSED, ARCHIVED.
     */
    public void setAdSetStatus(String accessToken, String metaAdSetId, String status) {
        ObjectNode body = MAPPER.createObjectNode().put("status", status);
        post(accessToken, "/" + metaAdSetId, body);
        log.info("Meta ad set {} → {}", metaAdSetId, status);
    }

    // ─── Insights ───────────────────────────────────────────────────────────

    /**
     * Fetch today's metrics for a campaign or ad set.
     * Returns: {"spend", "impressions", "clicks", "conversions"}
     * conversions = "actions" of type "offsite_conversion.fb_pixel_purchase" or "lead"
     */
    public Map<String, String> getInsights(String accessToken, String objectId) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/{id}/insights")
                .queryParam("fields", "spend,impressions,clicks,actions")
                .queryParam("date_preset", "today")
                .buildAndExpand(objectId)
                .toUriString();

        try {
            JsonNode resp = getWithAuth(accessToken, url);
            JsonNode data = resp != null ? resp.path("data") : null;
            if (data != null && data.isArray() && !data.isEmpty()) {
                JsonNode row = data.get(0);
                String conversions = extractConversions(row.path("actions"));
                return Map.of(
                        "spend",       row.path("spend").asText("0"),
                        "impressions", row.path("impressions").asText("0"),
                        "clicks",      row.path("clicks").asText("0"),
                        "conversions", conversions
                );
            }
            return Map.of("spend", "0", "impressions", "0", "clicks", "0", "conversions", "0");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Meta insights error: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }

    // ─── Custom Audiences (Phase 5-5) ──────────────────────────────────────────

    /**
     * Create or update a Meta Custom Audience from hashed email list.
     *
     * Custom audiences = hints to Advantage+ — NOT hard targeting (§25 п.12).
     * Only hashed emails (SHA-256, lowercase, trimmed) are sent — no PII.
     *
     * Returns the Meta custom audience ID.
     */
    public String upsertCustomAudience(
            String accessToken,
            String adAccountId,
            String name,
            String description,
            List<String> hashedEmails) {

        // Create the custom audience if it doesn't exist (idempotent by name via Meta dedup)
        ObjectNode createBody = MAPPER.createObjectNode()
                .put("name", name)
                .put("subtype", "CUSTOM")
                .put("description", description)
                .put("customer_file_source", "PARTNER_PROVIDED_ONLY");

        String audienceId = postAndGetId(accessToken, "/" + adAccountId + "/customaudiences", createBody);

        // Upload hashed emails to the audience
        if (!hashedEmails.isEmpty()) {
            ObjectNode uploadBody  = MAPPER.createObjectNode();
            ObjectNode payloadNode = MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode schemaNode = MAPPER.createArrayNode();
            schemaNode.add("EMAIL_SHA256");
            payloadNode.set("schema", schemaNode);

            com.fasterxml.jackson.databind.node.ArrayNode dataNode = MAPPER.createArrayNode();
            for (String email : hashedEmails) {
                com.fasterxml.jackson.databind.node.ArrayNode row = MAPPER.createArrayNode();
                row.add(email);
                dataNode.add(row);
            }
            payloadNode.set("data", dataNode);
            uploadBody.set("payload", payloadNode);

            post(accessToken, "/" + audienceId + "/users", uploadBody);
            log.info("Uploaded {} hashed emails to Meta audience {}", hashedEmails.size(), audienceId);
        }

        return audienceId;
    }

    /**
     * Exact set of Meta action_type values that count as conversions (audit M-18).
     * Using {@code contains("purchase")} caused double-counting because both
     * "offsite_conversion.fb_pixel_purchase" and parent roll-up types like "purchase"
     * appear in the same actions array — the same event was summed twice.
     */
    private static final Set<String> CONVERSION_ACTION_TYPES = Set.of(
            "offsite_conversion.fb_pixel_purchase",
            "offsite_conversion.fb_pixel_lead",
            "onsite_conversion.lead_grouped",
            "complete_registration"
    );

    /** Sums known conversion action types from Meta insights actions array (audit M-18). */
    private String extractConversions(JsonNode actions) {
        if (actions == null || !actions.isArray()) return "0";
        long total = 0;
        for (JsonNode action : actions) {
            String type = action.path("action_type").asText("");
            if (CONVERSION_ACTION_TYPES.contains(type)) {
                total += action.path("value").asLong(0);
            }
        }
        return String.valueOf(total);
    }

    // ─── Learning Phase (vision §6.5) ──────────────────────────────────────────

    /**
     * Read the current effective_status and learning_phase for an ad set.
     * Returns null if the API call fails (caller should treat as unknown phase).
     */
    public String getAdSetLearningPhase(String accessToken, String metaAdSetId) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/" + metaAdSetId)
                .queryParam("fields", "effective_status,learning_phase")
                .build()
                .toUriString();
        try {
            JsonNode resp = getWithAuth(accessToken, url);
            if (resp != null && resp.has("learning_phase")) {
                return resp.get("learning_phase").asText(null);
            }
            if (resp != null && resp.has("effective_status")) {
                return resp.get("effective_status").asText(null);
            }
            return null;
        } catch (Exception e) {
            log.warn("Could not read learning_phase for ad set {}: {}", metaAdSetId, e.getMessage());
            return null;
        }
    }

    // ─── Moderation probe (roadmap 9-8; arch §16.17) ──────────────────────────

    /**
     * GET /{ad_id}?fields=effective_status,ad_review_feedback for a probe ad (or ad set).
     * Returns {"effective_status": ..., "ad_review_feedback": "<json or empty>"}; empty map on failure.
     */
    public Map<String, String> getAdReviewStatus(String accessToken, String adId) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/" + adId)
                .queryParam("fields", "effective_status,ad_review_feedback")
                .build()
                .toUriString();
        try {
            JsonNode resp = getWithAuth(accessToken, url);
            if (resp == null) return Map.of();
            String feedback = resp.has("ad_review_feedback") && !resp.get("ad_review_feedback").isNull()
                    ? resp.get("ad_review_feedback").toString() : "";
            return Map.of("effective_status", resp.path("effective_status").asText(""),
                          "ad_review_feedback", feedback);
        } catch (Exception e) {
            log.warn("Could not read ad review status for {}: {}", adId, e.getMessage());
            return Map.of();
        }
    }

    // ─── Roadmap Phase 11: creatives, ads, read-back ─────────────────────────

    /** Chunk size for resumable video upload (8 MB keeps memory flat). */
    private static final int VIDEO_CHUNK = 8 * 1024 * 1024;

    /**
     * Resumable upload to /act_x/advideos (start → transfer* → finish). Returns the video id.
     */
    public String uploadVideo(String accessToken, String adAccountId, byte[] bytes, String fileName) {
        String path = "/" + adAccountId + "/advideos";
        MultiValueMap<String, Object> start = new LinkedMultiValueMap<>();
        start.add("upload_phase", "start");
        start.add("file_size", String.valueOf(bytes.length));
        JsonNode s = postMultipart(accessToken, path, start);
        String session = s.path("upload_session_id").asText(null);
        String videoId = s.path("video_id").asText(null);
        if (session == null || videoId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta advideos start returned no session/video id");
        }
        long startOffset = s.path("start_offset").asLong(0);
        long endOffset = s.path("end_offset").asLong(Math.min(bytes.length, VIDEO_CHUNK));
        int guard = 0;
        while (startOffset < bytes.length && startOffset < endOffset) {
            byte[] chunk = Arrays.copyOfRange(bytes, (int) startOffset, (int) Math.min(endOffset, bytes.length));
            MultiValueMap<String, Object> transfer = new LinkedMultiValueMap<>();
            transfer.add("upload_phase", "transfer");
            transfer.add("upload_session_id", session);
            transfer.add("start_offset", String.valueOf(startOffset));
            transfer.add("video_file_chunk", namedResource(chunk, fileName));
            // M-23: retry the transfer phase with exponential backoff (max 3 attempts: 1s, 2s, give up)
            JsonNode t = postMultipartWithRetry(accessToken, path, transfer, 3);
            long nextStart = t.path("start_offset").asLong(endOffset);
            long nextEnd = t.path("end_offset").asLong(Math.min(bytes.length, nextStart + VIDEO_CHUNK));
            if (nextStart <= startOffset) nextStart = endOffset;                     // defensive: always advance
            if (nextEnd <= nextStart) nextEnd = Math.min(bytes.length, nextStart + VIDEO_CHUNK);
            startOffset = nextStart;
            endOffset = nextEnd;
            if (++guard > 10_000) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta advideos transfer did not converge");
        }
        MultiValueMap<String, Object> finish = new LinkedMultiValueMap<>();
        finish.add("upload_phase", "finish");
        finish.add("upload_session_id", session);
        finish.add("title", fileName == null ? "upload" : fileName);
        JsonNode f = postMultipart(accessToken, path, finish);
        if (!f.path("success").asBoolean(true)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta advideos finish reported failure");
        }
        log.info("Meta video uploaded: {} ({} bytes)", videoId, bytes.length);
        return videoId;
    }

    /** POST /act_x/adimages (multipart). Returns the image hash. */
    public String uploadImage(String accessToken, String adAccountId, byte[] bytes, String fileName) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("filename", namedResource(bytes, fileName));
        JsonNode resp = postMultipart(accessToken, "/" + adAccountId + "/adimages", form);
        JsonNode images = resp.path("images");
        if (images.isObject() && images.elements().hasNext()) {
            JsonNode first = images.elements().next();
            String hash = first.path("hash").asText(null);
            if (hash != null) return hash;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta adimages returned no hash");
    }

    /**
     * POST /act_x/adcreatives. Video (object_story_spec.video_data) or image (link_data). url_tags carry
     * {{ad.id}} / {{placement}} / {{site_source_name}}; Advantage+ standard enhancements are opted out so a test
     * ad stays exactly one creative (roadmap Phase 11, rule 4).
     */
    public String createAdCreative(String accessToken, String adAccountId, String name, String pageId,
                                   String instagramActorId, String videoId, String imageHash, String thumbnailUrl,
                                   String primaryText, String headline, String linkUrl, String urlTags,
                                   String callToActionType) {
        ObjectNode body = MAPPER.createObjectNode().put("name", name);
        ObjectNode story = body.putObject("object_story_spec").put("page_id", pageId);
        if (instagramActorId != null && !instagramActorId.isBlank()) story.put("instagram_actor_id", instagramActorId);
        ObjectNode cta = MAPPER.createObjectNode().put("type", callToActionType == null ? "LEARN_MORE" : callToActionType);
        cta.putObject("value").put("link", linkUrl);
        if (videoId != null && !videoId.isBlank()) {
            ObjectNode video = story.putObject("video_data").put("video_id", videoId)
                    .put("title", headline).put("message", primaryText);
            if (thumbnailUrl != null && !thumbnailUrl.isBlank()) video.put("image_url", thumbnailUrl);
            video.set("call_to_action", cta);
        } else {
            ObjectNode link = story.putObject("link_data").put("link", linkUrl)
                    .put("message", primaryText).put("name", headline);
            if (imageHash != null && !imageHash.isBlank()) link.put("image_hash", imageHash);
            link.set("call_to_action", cta);
        }
        body.put("url_tags", urlTags);
        body.putObject("degrees_of_freedom_spec").putObject("creative_features_spec")
                .putObject("standard_enhancements").put("enroll_status", "OPT_OUT");
        return postAndGetId(accessToken, "/" + adAccountId + "/adcreatives", body);
    }

    /** POST /act_x/ads — always PAUSED; activation is a separate, gated RESUME. */
    public String createAd(String accessToken, String adAccountId, String metaAdSetId, String name, String metaCreativeId) {
        ObjectNode body = MAPPER.createObjectNode()
                .put("name", name)
                .put("adset_id", metaAdSetId)
                .put("status", "PAUSED");
        body.putObject("creative").put("creative_id", metaCreativeId);
        return postAndGetId(accessToken, "/" + adAccountId + "/ads", body);
    }

    /** Set ad status: ACTIVE, PAUSED, ARCHIVED, DELETED. */
    public void setAdStatus(String accessToken, String adId, String status) {
        ObjectNode body = MAPPER.createObjectNode().put("status", status);
        post(accessToken, "/" + adId, body);
        log.info("Meta ad {} → {}", adId, status);
    }

    /**
     * GET /act_x/ads with creative fields, following paging (max 20 pages). Rows:
     * {id, name, adset_id, campaign_id, status, effective_status, creative_id, video_id, image_hash, link_url, url_tags}.
     */
    public List<Map<String, String>> listAds(String accessToken, String adAccountId) {
        List<Map<String, String>> out = new ArrayList<>();
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/" + adAccountId + "/ads")
                .queryParam("fields", "id,name,adset_id,campaign_id,status,effective_status,creative{id,video_id,image_hash,object_story_spec,url_tags}")
                .queryParam("limit", 200)
                .build()
                .toUriString();
        for (int page = 0; page < 20 && url != null; page++) {
            JsonNode resp;
            try {
                resp = getWithAuth(accessToken, url);
            } catch (ResponseStatusException e) {
                throw e;
            } catch (HttpClientErrorException e) {
                log.error("Meta API GET ads failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
            }
            if (resp == null) break;
            for (JsonNode ad : resp.path("data")) out.add(adRow(ad));
            JsonNode next = resp.path("paging").path("next");
            url = next.isTextual() ? next.asText() : null;
        }
        return out;
    }

    /** Package-private for tests. */
    static Map<String, String> adRow(JsonNode ad) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("id", ad.path("id").asText(""));
        row.put("name", ad.path("name").asText(""));
        row.put("adset_id", ad.path("adset_id").asText(""));
        row.put("campaign_id", ad.path("campaign_id").asText(""));
        row.put("status", ad.path("status").asText(""));
        row.put("effective_status", ad.path("effective_status").asText(""));
        JsonNode c = ad.path("creative");
        row.put("creative_id", c.path("id").asText(""));
        row.put("video_id", c.path("video_id").asText(""));
        row.put("image_hash", c.path("image_hash").asText(""));
        row.put("url_tags", c.path("url_tags").asText(""));
        JsonNode spec = c.path("object_story_spec");
        String link = spec.path("video_data").path("call_to_action").path("value").path("link").asText("");
        if (link.isEmpty()) link = spec.path("link_data").path("link").asText("");
        row.put("link_url", link);
        if (row.get("video_id").isEmpty()) row.put("video_id", spec.path("video_data").path("video_id").asText(""));
        if (row.get("image_hash").isEmpty()) row.put("image_hash", spec.path("link_data").path("image_hash").asText(""));
        return row;
    }

    private static ByteArrayResource namedResource(byte[] bytes, String fileName) {
        return new ByteArrayResource(bytes) {
            @Override public String getFilename() { return fileName == null ? "upload" : fileName; }
        };
    }

    /**
     * Retry wrapper for chunked video upload transfer phase (audit M-23).
     * Backs off exponentially: delay = 1000 ms × 2^(attempt-1); gives up after {@code maxAttempts}.
     */
    private JsonNode postMultipartWithRetry(String accessToken, String path,
            MultiValueMap<String, Object> form, int maxAttempts) {
        Exception last = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                return postMultipart(accessToken, path, form);
            } catch (Exception e) {
                last = e;
                if (attempt + 1 < maxAttempts) {
                    long delayMs = 1000L << attempt; // 1 s, 2 s
                    log.warn("Meta advideos chunk transfer failed (attempt {}/{}), retrying in {} ms: {}",
                            attempt + 1, maxAttempts, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (last instanceof ResponseStatusException rse) throw rse;
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Meta advideos chunk transfer failed after " + maxAttempts + " attempts: " + (last != null ? last.getMessage() : "unknown"));
    }

    private JsonNode postMultipart(String accessToken, String path, MultiValueMap<String, Object> form) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path(path)
                .build()
                .toUriString();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(accessToken);   // S-3: token in Authorization header, never in query string
        try {
            JsonNode resp = rest.postForObject(url, new HttpEntity<>(form, headers), JsonNode.class);
            if (resp == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API returned empty body for " + path);
            return resp;
        } catch (HttpClientErrorException e) {
            log.error("Meta API multipart POST {} failed {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }

    // ─── Internal helpers ───────────────────────────────────────────────────

    /**
     * Build HttpHeaders with the Meta access token as a Bearer token (audit S-3).
     * Tokens MUST NOT appear in the query string — they would be logged by every proxy and CDN.
     */
    private static HttpHeaders authHeaders(String accessToken, MediaType contentType) {
        HttpHeaders h = new HttpHeaders();
        if (contentType != null) h.setContentType(contentType);
        h.setBearerAuth(accessToken);
        return h;
    }

    /**
     * GET {BASE_URL}/{path} with the access token in the Authorization header (audit S-3).
     */
    private JsonNode getWithAuth(String accessToken, String url) {
        HttpEntity<Void> req = new HttpEntity<>(authHeaders(accessToken, null));
        try {
            return rest.exchange(url, org.springframework.http.HttpMethod.GET, req, JsonNode.class).getBody();
        } catch (HttpClientErrorException e) {
            log.error("Meta API GET {} failed {}: {}", url, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }

    private String postAndGetId(String accessToken, String path, ObjectNode body) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path(path)
                .build()
                .toUriString();

        try {
            JsonNode resp = rest.postForObject(url,
                    new HttpEntity<>(body.toString(), authHeaders(accessToken, MediaType.APPLICATION_JSON)),
                    JsonNode.class);
            if (resp == null || !resp.has("id")) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Meta API returned no ID for path " + path);
            }
            return resp.get("id").asText();
        } catch (HttpClientErrorException e) {
            log.error("Meta API POST {} failed {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }

    private void post(String accessToken, String path, ObjectNode body) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path(path)
                .build()
                .toUriString();

        try {
            rest.postForObject(url,
                    new HttpEntity<>(body.toString(), authHeaders(accessToken, MediaType.APPLICATION_JSON)),
                    String.class);
        } catch (HttpClientErrorException e) {
            log.error("Meta API POST {} failed {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }
}
