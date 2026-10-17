package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Weekly taxonomy recalibration (arch §15.10):
 * merges near-duplicate centroids (cosine ≥ 0.92) and promotes mature CANDIDATEs → CONFIRMED.
 * Runs every Sunday at 04:00 UTC.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaxonomyRecalibrateScheduler {

    private final RestTemplate  restTemplate;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 4 * * SUN", zone = "UTC")
    public void recalibrate() {
        String url = appProperties.getMlServiceUrl() + "/taxonomy/recalibrate";
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.exchange(
                url, HttpMethod.POST, new HttpEntity<>(headers), Map.class).getBody();
            log.info("Taxonomy recalibrate scheduled run: {}", result);
        } catch (Exception e) {
            log.error("Taxonomy recalibrate scheduled run failed: {}", e.getMessage());
        }
    }
}
