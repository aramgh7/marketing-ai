package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MlServiceClient {

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    /**
     * Submits a creative for async DNA extraction.
     *
     * @return job_id from ml-service, or null if the call fails
     */
    public String analyzeCreative(UUID tenantId, UUID creativeId,
                                   String mediaType, String fileUrl,
                                   String thumbnailUrl, String transcript,
                                   Float durationSec) {
        String url = appProperties.getMlServiceUrl() + "/analyze/creative";

        Map<String, Object> body = new HashMap<>();
        body.put("tenant_id", tenantId.toString());
        body.put("creative_id", creativeId.toString());
        body.put("media_type", mediaType);
        body.put("file_url", fileUrl);
        body.put("thumbnail_url", thumbnailUrl);
        body.put("transcript", transcript);
        body.put("duration_sec", durationSec);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            if (response != null && response.containsKey("job_id")) {
                return (String) response.get("job_id");
            }
        } catch (Exception ex) {
            log.error("ml-service call failed for creative {}: {}", creativeId, ex.getMessage());
        }
        return null;
    }

    /** Generic GET from ml-service (roadmap Phase 11: /decide/pairs). */
    public <T> T get(String pathWithQuery, Class<T> responseType) {
        String url = appProperties.getMlServiceUrl() + pathWithQuery;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), responseType).getBody();
    }

    /** Generic POST to ml-service with JSON body. */
    @SuppressWarnings("unchecked")
    public <T> T post(String path, Object body, Class<T> responseType) {
        String url = appProperties.getMlServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        return restTemplate.postForObject(url, new HttpEntity<>(body, headers), responseType);
    }
}
