package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Daily refresh of niche_metric_benchmarks from FIRST-PARTY data only (vision §25 p.19).
 * Never reads Meta Platform data — only from form_submissions, appointments, reward_events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NicheBenchmarkScheduler {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void refreshBenchmarks() {
        log.info("NicheBenchmarkScheduler: refreshing niche benchmarks (first-party only)");

        // D-4: avoid Cartesian product — pre-aggregate each source table independently via CTEs
        // before joining to tenants; direct multi-table LEFT JOINs without a cross-table predicate
        // multiply rows (N appointments × M reward_events × K form_submissions per tenant).
        List<Map<String, Object>> tenantData = jdbc.queryForList("""
            WITH appt_agg AS (
                SELECT tenant_id,
                       COUNT(*)                        AS booked,
                       AVG(booking_distance_h)         AS avg_booking_distance_h
                FROM   appointments
                WHERE  created_at >= NOW() - INTERVAL '30 days'
                GROUP BY tenant_id
            ),
            reward_agg AS (
                SELECT tenant_id,
                       COUNT(DISTINCT session_id)      AS held
                FROM   reward_events
                WHERE  is_quality_stage = TRUE
                  AND  rewarded_at >= NOW() - INTERVAL '30 days'
                GROUP BY tenant_id
            ),
            form_agg AS (
                SELECT tenant_id,
                       COUNT(*)                        AS form_submits
                FROM   form_submissions
                WHERE  submitted_at >= NOW() - INTERVAL '30 days'
                GROUP BY tenant_id
            )
            SELECT
                t.id::text                              AS tenant_id,
                COALESCE(t.niche_key, 'generic')        AS niche_key,
                COALESCE(aa.booked, 0)                  AS booked,
                COALESCE(ra.held, 0)                    AS held,
                COALESCE(fa.form_submits, 0)            AS form_submits,
                aa.avg_booking_distance_h               AS avg_booking_distance_h
            FROM tenants t
            LEFT JOIN appt_agg   aa ON aa.tenant_id = t.id
            LEFT JOIN reward_agg ra ON ra.tenant_id = t.id
            LEFT JOIN form_agg   fa ON fa.tenant_id = t.id
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
                appProperties.getMlServiceUrl() + "/attribution/benchmarks/compute", req, List.class);

            if (benchmarks == null) return;

            for (Map<String, Object> bm : benchmarks) {
                jdbc.update("""
                    INSERT INTO niche_metric_benchmarks (niche_key, metric_key, value, tenant_count, data_window_days)
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
