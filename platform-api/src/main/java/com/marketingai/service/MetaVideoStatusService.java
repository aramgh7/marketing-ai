package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Graph API lookups for the publish flow (audit M-4, M-20): video processing status and thumbnails after
 * {@code /advideos}, delivery status of the ad set / campaign before RESUME. Never writes — the Meta write policy
 * (MetaWriteGate) is not involved. Separate from MetaApiService on purpose: timeouts, Bearer auth, no multipart.
 */
@Slf4j
@Service
public class MetaVideoStatusService {

    static final String BASE_URL = "https://graph.facebook.com/v20.0";

    private final RestTemplate rest;
    private final int thumbnailAttempts;
    private final long thumbnailIntervalMs;

    @Autowired
    public MetaVideoStatusService(RestTemplateBuilder builder,
                                  @Value("${app.meta.thumbnail-poll-attempts:6}") int thumbnailAttempts,
                                  @Value("${app.meta.thumbnail-poll-interval-ms:5000}") long thumbnailIntervalMs) {
        this(builder.connectTimeout(Duration.ofSeconds(10)).readTimeout(Duration.ofSeconds(30)).build(),
                thumbnailAttempts, thumbnailIntervalMs);
    }

    /** Package-private for tests. */
    MetaVideoStatusService(RestTemplate rest, int thumbnailAttempts, long thumbnailIntervalMs) {
        this.rest = rest;
        this.thumbnailAttempts = Math.max(1, thumbnailAttempts);
        this.thumbnailIntervalMs = Math.max(0, thumbnailIntervalMs);
    }

    /** {@code GET /{video_id}?fields=status} → status.video_status: ready | processing | error ("" when unknown). */
    public String videoStatus(String accessToken, String videoId) {
        JsonNode n = get(accessToken, "/" + videoId, "status");
        return n == null ? "" : n.path("status").path("video_status").asText("");
    }

    /** {@code GET /{video_id}/thumbnails} → [{uri, is_preferred}] (empty when none yet or on failure). */
    public List<Map<String, Object>> thumbnails(String accessToken, String videoId) {
        JsonNode n = get(accessToken, "/" + videoId + "/thumbnails", null);
        List<Map<String, Object>> out = new ArrayList<>();
        if (n == null) return out;
        for (JsonNode t : n.path("data")) {
            Map<String, Object> m = new HashMap<>();
            m.put("uri", t.path("uri").asText(null));
            m.put("is_preferred", t.path("is_preferred").asBoolean(false));
            out.add(m);
        }
        return out;
    }

    /**
     * Meta renders thumbnails asynchronously after the upload: poll up to {@code app.meta.thumbnail-poll-attempts}
     * times with {@code app.meta.thumbnail-poll-interval-ms} between attempts. Returns the preferred thumbnail URL,
     * or null when the video is still processing; 502 when Meta reports a processing error.
     */
    public String awaitThumbnail(String accessToken, String videoId) {
        for (int attempt = 1; attempt <= thumbnailAttempts; attempt++) {
            String status = videoStatus(accessToken, videoId);
            if ("error".equalsIgnoreCase(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta could not process video " + videoId);
            }
            String uri = CreativePublishMath.preferredThumbnail(thumbnails(accessToken, videoId));
            if (uri != null) return uri;
            log.info("Meta video {} status={} — no thumbnail yet (attempt {}/{})", videoId, status, attempt, thumbnailAttempts);
            if (attempt < thumbnailAttempts) sleep();
        }
        return null;
    }

    public record DeliveryStatus(String adSetEffectiveStatus, String campaignEffectiveStatus) {
        public static final DeliveryStatus UNKNOWN = new DeliveryStatus(null, null);
    }

    /** {@code GET /{adset_id}?fields=effective_status,campaign{effective_status}}; UNKNOWN (nulls) on failure. */
    public DeliveryStatus adSetDelivery(String accessToken, String adSetId) {
        if (adSetId == null || adSetId.isBlank()) return DeliveryStatus.UNKNOWN;
        JsonNode n = get(accessToken, "/" + adSetId, "effective_status,campaign{effective_status}");
        if (n == null) return DeliveryStatus.UNKNOWN;
        return new DeliveryStatus(blankToNull(n.path("effective_status").asText(null)),
                blankToNull(n.path("campaign").path("effective_status").asText(null)));
    }

    private JsonNode get(String accessToken, String path, String fields) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUri(URI.create(BASE_URL)).path(path);
        if (fields != null) b.queryParam("fields", fields);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        try {
            return rest.exchange(b.build().toUriString(), HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
        } catch (Exception e) {
            log.warn("Meta GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(thumbnailIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
