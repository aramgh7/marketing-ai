package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Daily refresh of niche_benchmarks from FIRST-PARTY data only (vision §25 p.19).
 * Never reads Meta Platform data — only from form_submissions, appointments, reward_events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NicheBenchmarkScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void refreshBenchmarks() {
        log.info("NicheBenchmarkScheduler: refreshing niche benchmarks (first-party only)");

        List<Map<String, Object>> tenantData = jdbc.queryForList("""
            SELECT
                t.id::text                        AS tenant_id,
                COALESCE(t.niche_key, 'generic')  AS niche_key,
                COUNT(DISTINCT a.id) FILTER (
                    WHERE a.created_at >= NOW() - INTERVAL '30 days'
                )                                 AS booked,
                COUNT(DISTINCT re.session_id) FILTER (
                    WHERE re.is_quality_stage = TRUE
                    AND re.rewarded_at >= NOW() - INTERVAL '30 days'
                )                                 AS held,
                COUNT(DISTINCT fs.id) FILTER (
                    WHERE fs.submitted_at >= NOW() - INTERVAL '30 days'
                )                                 AS form_submits,
                AVG(a.booking_distance_h) FILTER (
                    WHERE a.created_at >= NOW() - INTERVAL '30 days'
                )                                 AS avg_booking_distance_h
            FROM tenants t
            LEFT JOIN appointments    a  ON a.tenant_id = t.id
            LEFT JOIN reward_events   re ON re.tenant_id = t.id
            LEFT JOIN form_submissions fs ON fs.tenant_id = t.id
            GROUP BY t.id, t.niche_key
            """);

        if (tenantData.isEmpty()) {
            log.info("NicheBenchmarkScheduler: no tenant data, skipping");
            return;
        }

        try {
            Map<String, Object> req = Map.of(
                "tenant_funnel_data", tenantData,
                "data_window_days",   30
            );
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> benchmarks = restTemplate.postForObject(
                "http://ml-service:8001/attribution/benchmarks/compute", req, List.class);

            if (benchmarks == null) return;

            for (Map<String, Object> bm : benchmarks) {
                jdbc.update("""
                    INSERT INTO niche_benchmarks (niche_key, metric_key, value, tenant_count, data_window_days)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    bm.get("niche_key"), bm.get("metric_key"),
                    bm.get("value"),     bm.get("tenant_count"),
                    bm.getOrDefault("data_window_days", 30));
            }
            log.info("NicheBenchmarkScheduler: {} rows inserted", benchmarks.size());
        } catch (Exception e) {
            log.error("NicheBenchmarkScheduler error: {}", e.getMessage());
        }
    }
}
