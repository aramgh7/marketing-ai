package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Synthetic form health check every 30 minutes (vision §11.4).
 * After 3 consecutive PASSes, alerts marketer to confirm resume if landing was paused.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormHealthScheduler {

    private final JdbcTemplate  jdbc;
    private final RestTemplate  restTemplate;
    private final AlertService  alertService;

    @Scheduled(fixedDelay = 1_800_000)
    public void checkFormHealth() {
        List<Map<String, Object>> landings;
        try {
            landings = jdbc.queryForList("""
                SELECT id, tenant_id, form_endpoint_url,
                       consecutive_form_passes, form_paused
                FROM landing_variants
                WHERE is_active = TRUE AND form_endpoint_url IS NOT NULL
                LIMIT 50
                """);
        } catch (Exception e) {
            log.warn("FormHealthScheduler: query failed — {}", e.getMessage());
            return;
        }

        for (Map<String, Object> landing : landings) {
            String url = (String) landing.get("form_endpoint_url");
            if (url == null) continue;

            try {
                Map<String, Object> req = Map.of("form_endpoint_url", url);
                @SuppressWarnings("unchecked")
                Map<String, Object> result = restTemplate.postForObject(
                    "http://ml-service:8001/analyze/form-health", req, Map.class);

                boolean ok = Boolean.TRUE.equals(result != null ? result.get("ok") : null);
                int currentPasses = landing.get("consecutive_form_passes") instanceof Number n
                    ? n.intValue() : 0;

                if (ok) {
                    int newPasses = currentPasses + 1;
                    jdbc.update(
                        "UPDATE landing_variants SET consecutive_form_passes = ? WHERE id = ?",
                        newPasses, landing.get("id"));
                    if (newPasses >= 3 && Boolean.TRUE.equals(landing.get("form_paused"))) {
                        alertService.sendAlert(
                            "Form health: 3 consecutive passes for landing " + landing.get("id")
                            + ". Marketer confirmation required to resume.");
                        log.info("FormHealthScheduler: resume alert sent for landing {}", landing.get("id"));
                    }
                } else {
                    jdbc.update(
                        "UPDATE landing_variants SET consecutive_form_passes = 0 WHERE id = ?",
                        landing.get("id"));
                    log.warn("FormHealthScheduler: form check FAILED for landing {}: {}",
                        landing.get("id"), result != null ? result.get("error") : "null response");
                }
            } catch (Exception e) {
                log.warn("FormHealthScheduler: error checking landing {}: {}",
                    landing.get("id"), e.getMessage());
            }
        }
    }
}
