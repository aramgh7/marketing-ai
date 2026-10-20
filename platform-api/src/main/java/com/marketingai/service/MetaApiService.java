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

import java.net.URI;
import java.util.List;
import java.util.Map;

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
                .queryParam("access_token", accessToken)
                .buildAndExpand(objectId)
                .toUriString();

        try {
            JsonNode resp = rest.getForObject(url, JsonNode.class);
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
        } catch (HttpClientErrorException e) {
            log.error("Meta insights error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
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

    /** Sums purchase + lead conversion action types from Meta insights actions array */
    private String extractConversions(JsonNode actions) {
        if (actions == null || !actions.isArray()) return "0";
        long total = 0;
        for (JsonNode action : actions) {
            String type = action.path("action_type").asText("");
            if (type.contains("purchase") || type.contains("lead") || type.equals("complete_registration")) {
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
                .queryParam("access_token", accessToken)
                .build()
                .toUriString();
        try {
            JsonNode resp = rest.getForObject(url, JsonNode.class);
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
                .queryParam("access_token", accessToken)
                .build()
                .toUriString();
        try {
            JsonNode resp = rest.getForObject(url, JsonNode.class);
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

    // ─── Internal helpers ───────────────────────────────────────────────────

    private String postAndGetId(String accessToken, String path, ObjectNode body) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path(path)
                .queryParam("access_token", accessToken)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            JsonNode resp = rest.postForObject(url, new HttpEntity<>(body.toString(), headers), JsonNode.class);
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
                .queryParam("access_token", accessToken)
                .build()
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            rest.postForObject(url, new HttpEntity<>(body.toString(), headers), String.class);
        } catch (HttpClientErrorException e) {
            log.error("Meta API POST {} failed {}: {}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Meta API error: " + e.getMessage());
        }
    }
}
