package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * Monthly match-score calibration report (vision §6.3a).
 * Loads last month's predictions + actuals, calls FastAPI /analyze/calibration,
 * alerts via AlertService if ECE > 0.10.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchScoreCalibrationScheduler {

    private final JdbcTemplate       jdbc;
    private final RestTemplate        restTemplate;
    private final AlertService        alertService;
    private final AppProperties       appProperties;

    /** 1st of month, 07:00 UTC */
    @Scheduled(cron = "0 0 7 1 * *", zone = "UTC")
    public void runCalibration() {
        log.info("MatchScoreCalibrationScheduler: running monthly match-score calibration");

        LocalDate end   = LocalDate.now().withDayOfMonth(1).minusDays(1);
        LocalDate start = end.withDayOfMonth(1);

        // match_score lives on landings; join via landing_id on sessions
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT l.match_score AS score,
                       (EXISTS (
                           SELECT 1 FROM events e
                           WHERE e.session_id = s.id AND e.event_type = 'CONVERSION'
                       )) AS converted
                FROM sessions s
                JOIN landings l ON l.id = s.landing_id
                WHERE l.match_score IS NOT NULL
                  AND s.created_at BETWEEN ? AND ?
                LIMIT 50000
                """, start.atStartOfDay(), end.plusDays(1).atStartOfDay());

        if (rows.isEmpty()) {
            log.info("MatchScoreCalibrationScheduler: no sessions with match_score in {}/{}", start, end);
            return;
        }

        Map<String, Object> req = Map.of("predictions_and_actuals", rows);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        Map<?, ?> result;
        try {
            result = restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/analyze/calibration",
                    new HttpEntity<>(req, headers), Map.class);
        } catch (Exception e) {
            log.error("MatchScoreCalibrationScheduler: FastAPI call failed: {}", e.getMessage());
            return;
        }

        if (result == null) return;

        Object eceObj = result.get("ece");
        double ece = eceObj instanceof Number ? ((Number) eceObj).doubleValue() : -1.0;
        log.info("MatchScoreCalibrationScheduler: ECE={} for {}/{}", ece, start, end);

        if (ece > 0.10) {
            alertService.sendAlert(
                    String.format("⚠️ Match-score calibration alert: ECE=%.4f (>0.10) for %s/%s. " +
                            "Consider retraining the match-score model (vision §6.3a).", ece, start, end));
        }
    }
}
