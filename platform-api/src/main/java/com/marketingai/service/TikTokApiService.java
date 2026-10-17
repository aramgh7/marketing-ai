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
 * TikTok Ads API v1.3 wrapper (Phase 7).
 * Docs: https://business-api.tiktok.com/portal/docs
 */
@Slf4j
@Service
public class TikTokApiService {

    private static final String BASE_URL = "https://business-api.tiktok.com/open_api/v1.3";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = new RestTemplate();

    public List<Map<String, Object>> getCampaigns(String accessToken, String advertiserId) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/campaign/get/")
                .queryParam("advertiser_id", advertiserId)
                .build()
                .toUriString();

        HttpHeaders headers = buildHeaders(accessToken);

        try {
            JsonNode resp = rest.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();

            List<Map<String, Object>> campaigns = new ArrayList<>();
            if (resp != null) {
                JsonNode list = resp.path("data").path("list");
                if (list.isArray()) {
                    for (JsonNode c : list) {
                        campaigns.add(Map.of(
                                "id",     c.path("campaign_id").asText(),
                                "name",   c.path("campaign_name").asText(),
                                "status", c.path("operation_status").asText()
                        ));
                    }
                }
            }
            return campaigns;
        } catch (HttpClientErrorException e) {
            log.error("TikTok getCampaigns failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TikTok Ads API error: " + e.getMessage());
        }
    }

    public Map<String, Object> getCampaignInsights(
            String accessToken, String advertiserId,
            String campaignId, String startDate, String endDate) {

        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/report/integrated/get/")
                .queryParam("advertiser_id", advertiserId)
                .queryParam("report_type", "BASIC")
                .queryParam("data_level", "AUCTION_CAMPAIGN")
                .queryParam("dimensions", "[\"campaign_id\"]")
                .queryParam("metrics", "[\"spend\",\"impressions\",\"clicks\",\"conversions\"]")
                .queryParam("filters", "[{\"field_name\":\"campaign_id\",\"filter_type\":\"IN\",\"filter_value\":\"[\\\"" + campaignId + "\\\"]\"}]")
                .queryParam("start_date", startDate)
                .queryParam("end_date", endDate)
                .build()
                .toUriString();

        HttpHeaders headers = buildHeaders(accessToken);

        try {
            JsonNode resp = rest.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), JsonNode.class).getBody();

            if (resp != null) {
                JsonNode list = resp.path("data").path("list");
                if (list.isArray() && !list.isEmpty()) {
                    JsonNode metrics = list.get(0).path("metrics");
                    return Map.of(
                            "spend",       metrics.path("spend").asDouble(0),
                            "impressions", metrics.path("impressions").asLong(0),
                            "clicks",      metrics.path("clicks").asLong(0),
                            "conversions", metrics.path("conversions").asLong(0)
                    );
                }
            }
            return Map.of("spend", 0.0, "impressions", 0L, "clicks", 0L, "conversions", 0L);
        } catch (HttpClientErrorException e) {
            log.error("TikTok getInsights failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TikTok Ads API error: " + e.getMessage());
        }
    }

    public void setCampaignStatus(String accessToken, String advertiserId,
                                  String campaignId, String status) {
        String url = UriComponentsBuilder.fromUri(URI.create(BASE_URL))
                .path("/campaign/status/update/")
                .build()
                .toUriString();

        ObjectNode body = MAPPER.createObjectNode()
                .put("advertiser_id", advertiserId)
                .put("operation_status", status);
        body.putArray("campaign_ids").add(campaignId);

        HttpHeaders headers = buildHeaders(accessToken);

        try {
            rest.postForObject(url, new HttpEntity<>(body.toString(), headers), String.class);
            log.info("TikTok campaign {} → {}", campaignId, status);
        } catch (HttpClientErrorException e) {
            log.error("TikTok setStatus failed {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TikTok Ads API error: " + e.getMessage());
        }
    }

    private HttpHeaders buildHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Access-Token", accessToken);
        return headers;
    }
}
