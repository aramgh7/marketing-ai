package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.*;

/**
 * Google Ads API v17 wrapper (Phase 7).
 * Docs: https://developers.google.com/google-ads/api/docs/start
 */
@Slf4j
@Service
public class GoogleAdsApiService {

    private static final String BASE_URL = "https://googleads.googleapis.com/v17";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = new RestTemplate();

    public List<Map<String, Object>> getCampaigns(String accessToken, String customerId) {
        String gaql = "SELECT campaign.id, campaign.name, campaign.status, " +
                "campaign.advertising_channel_type " +
                "FROM campaign WHERE campaign.status != 'REMOVED'";

        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/customers/{customerId}/googleAds:searchStream")
                .buildAndExpand(customerId)
                .toUriString();

        ObjectNode body = MAPPER.createObjectNode().put("query", gaql);
        HttpHeaders headers = buildHeaders(accessToken);

        try {
            JsonNode resp = rest.postForObject(
                    url, new HttpEntity<>(body.toString(), headers), JsonNode.class);
            List<Map<String, Object>> campaigns = new ArrayList<>();
            if (resp != null && resp.isArray()) {
                for (JsonNode batch : resp) {
                    JsonNode results = batch.path("results");
                    if (results.isArray()) {
                        for (JsonNode row : results) {
                            JsonNode c = row.path("campaign");
                            campaigns.add(Map.of(
                                    "id",      c.path("id").asText(),
                                    "name",    c.path("name").asText(),
                                    "status",  c.path("status").asText(),
                                    "channel", c.path("advertisingChannelType").asText()
                            ));
                        }
                    }
                }
            }
            return campaigns;
        } catch (HttpClientErrorException e) {
            log.error("Google Ads getCampaigns failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Ads API error: " + e.getMessage());
        }
    }

    public Map<String, String> getCampaignInsights(
            String accessToken, String customerId, String campaignId, String dateRangeType) {
        if (!customerId.matches("[\\d\\-]+") || !campaignId.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Google Ads IDs");
        }
        String resourceName = "customers/" + customerId + "/campaigns/" + campaignId;
        String gaql = "SELECT campaign.id, metrics.impressions, metrics.clicks, " +
                "metrics.cost_micros, metrics.conversions " +
                "FROM campaign " +
                "WHERE campaign.resource_name = '" + resourceName + "' " +
                "AND segments.date DURING " + dateRangeType;

        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/customers/{customerId}/googleAds:searchStream")
                .buildAndExpand(customerId)
                .toUriString();

        ObjectNode body = MAPPER.createObjectNode().put("query", gaql);
        HttpHeaders headers = buildHeaders(accessToken);

        try {
            JsonNode resp = rest.postForObject(
                    url, new HttpEntity<>(body.toString(), headers), JsonNode.class);

            if (resp != null && resp.isArray() && !resp.isEmpty()) {
                JsonNode results = resp.get(0).path("results");
                if (results.isArray() && !results.isEmpty()) {
                    JsonNode metrics = results.get(0).path("metrics");
                    long costMicros = metrics.path("costMicros").asLong(0);
                    return Map.of(
                            "impressions", metrics.path("impressions").asText("0"),
                            "clicks",      metrics.path("clicks").asText("0"),
                            "spend",       String.valueOf(costMicros / 1_000_000.0),
                            "conversions", metrics.path("conversions").asText("0")
                    );
                }
            }
            return Map.of("impressions", "0", "clicks", "0", "spend", "0", "conversions", "0");
        } catch (HttpClientErrorException e) {
            log.error("Google Ads getInsights failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Ads API error: " + e.getMessage());
        }
    }

    public void setCampaignStatus(String accessToken, String customerId, String campaignId, String status) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/customers/{customerId}/campaigns/{campaignId}")
                .buildAndExpand(customerId, campaignId)
                .toUriString();

        ObjectNode body = MAPPER.createObjectNode().put("status", status);
        HttpHeaders headers = buildHeaders(accessToken);
        headers.set("X-HTTP-Method-Override", "PATCH");

        try {
            rest.postForObject(url, new HttpEntity<>(body.toString(), headers), String.class);
            log.info("Google Ads campaign {} → {}", campaignId, status);
        } catch (HttpClientErrorException e) {
            log.error("Google Ads setStatus failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google Ads API error: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);
        return headers;
    }
}
