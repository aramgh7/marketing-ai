package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Monthly retention sweep: materialise aggregates then delete expired rows.
 * Retention policy:
 *   events / sessions        13 months
 *   decision_log             25 months
 *   high-frequency log tables (D-20):
 *     campaign_cpm_history   90 days  (hourly Meta syncs accumulate fast)
 *     thompson_reward_log    13 months
 *     anchor_divergence_log  13 months
 *     daily_brief_log        13 months
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 2 1 * *", zone = "UTC")
    public void runRetentionSweep() {
        log.info("RetentionScheduler: starting retention sweep");

        LocalDate cutoffEvents = LocalDate.now().minusMonths(13);

        // Materialise variant aggregates before dropping old events
        jdbc.update("""
                INSERT INTO agg_variant_daily (tenant_id, variant_id, day, sessions, conversions, revenue)
                SELECT tenant_id, variant_id::uuid, event_time::date AS day,
                       COUNT(DISTINCT session_id)                                           AS sessions,
                       COUNT(CASE WHEN event_type = 'CONVERSION' THEN 1 END)               AS conversions,
                       COALESCE(SUM(CASE WHEN event_type = 'PURCHASE' THEN revenue ELSE 0 END), 0) AS revenue
                FROM events
                WHERE event_time < ?::timestamptz
                  AND variant_id IS NOT NULL
                GROUP BY tenant_id, variant_id, event_time::date
                ON CONFLICT (tenant_id, variant_id, day) DO UPDATE
                    SET sessions    = EXCLUDED.sessions    + agg_variant_daily.sessions,
                        conversions = EXCLUDED.conversions + agg_variant_daily.conversions,
                        revenue     = EXCLUDED.revenue     + agg_variant_daily.revenue
                """, cutoffEvents.atStartOfDay());

        // Materialise creative aggregates
        jdbc.update("""
                INSERT INTO agg_creative_daily (tenant_id, creative_id, day, sessions, conversions, revenue)
                SELECT tenant_id, creative_id, event_time::date AS day,
                       COUNT(DISTINCT session_id)                                           AS sessions,
                       COUNT(CASE WHEN event_type = 'CONVERSION' THEN 1 END)               AS conversions,
                       COALESCE(SUM(CASE WHEN event_type = 'PURCHASE' THEN revenue ELSE 0 END), 0) AS revenue
                FROM events
                WHERE event_time < ?::timestamptz
                  AND creative_id IS NOT NULL
                GROUP BY tenant_id, creative_id, event_time::date
                ON CONFLICT (tenant_id, creative_id, day) DO UPDATE
                    SET sessions    = EXCLUDED.sessions    + agg_creative_daily.sessions,
                        conversions = EXCLUDED.conversions + agg_creative_daily.conversions,
                        revenue     = EXCLUDED.revenue     + agg_creative_daily.revenue
                """, cutoffEvents.atStartOfDay());

        int deletedEvents = jdbc.update(
                "DELETE FROM events WHERE event_time < ?::timestamptz",
                cutoffEvents.atStartOfDay());

        int deletedSessions = jdbc.update(
                "DELETE FROM sessions WHERE started_at < ?::timestamptz",
                cutoffEvents.atStartOfDay());

        LocalDate cutoffDecisions = LocalDate.now().minusMonths(25);
        int deletedDecisions = jdbc.update(
                "DELETE FROM decision_log WHERE decided_at < ?::timestamptz",
                cutoffDecisions.atStartOfDay());

        log.info("RetentionScheduler: deleted {} events, {} sessions, {} decision_log rows",
                deletedEvents, deletedSessions, deletedDecisions);

        // D-20: high-frequency log tables — shorter retention windows
        LocalDate cutoffCpm      = LocalDate.now().minusDays(90);
        LocalDate cutoffLogs13mo = LocalDate.now().minusMonths(13);

        int deletedCpm = jdbc.update(
                "DELETE FROM campaign_cpm_history WHERE recorded_at < ?::timestamptz",
                cutoffCpm.atStartOfDay());

        int deletedRewardLog = jdbc.update(
                "DELETE FROM thompson_reward_log WHERE applied_at < ?::timestamptz",
                cutoffLogs13mo.atStartOfDay());

        int deletedDivergenceLog = jdbc.update(
                "DELETE FROM anchor_divergence_log WHERE created_at < ?::timestamptz",
                cutoffLogs13mo.atStartOfDay());

        int deletedBriefLog = jdbc.update(
                "DELETE FROM daily_brief_log WHERE sent_at < ?::timestamptz",
                cutoffLogs13mo.atStartOfDay());

        log.info("RetentionScheduler: high-freq logs deleted — cpm_history={}, reward_log={}, divergence_log={}, brief_log={}",
                deletedCpm, deletedRewardLog, deletedDivergenceLog, deletedBriefLog);
    }
}
