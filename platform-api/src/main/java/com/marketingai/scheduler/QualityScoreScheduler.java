package com.marketingai.scheduler;

import com.marketingai.service.QualityScoreMath;
import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Computes sessions.quality_score for recently closed sessions
 * and updates creative_quality aggregate table.
 * Quality Score is a protective metric only — not added to Thompson arms (arch §4.4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityScoreScheduler {

    private final JdbcTemplate jdbc;
    private final AppProperties appProperties;

    /**
     * D-2: default look-back window — sessions closed since at most this many days ago.
     * On first run (no previous cutoff tracking) this ensures we don't leave a backlog
     * of un-scored sessions.  The "closed" filter (last_event_at < now()-30min) keeps the
     * two conditions from being contradictory, unlike the previous 16-minute window.
     */
    private static final int DEFAULT_LOOKBACK_DAYS = 7;

    @Scheduled(fixedDelay = 900_000) // every 15 minutes
    public void computeQualityScores() {
        // D-2: use last 7 days so the window is never empty on first run or after a restart.
        // Sessions must still be "closed" (last_event_at < now()-30min) so the two conditions
        // are always satisfiable.
        Instant cutoff = Instant.now().minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS);

        // Sessions closed in the last 7 days that don't yet have a quality_score
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id, s.tenant_id, s.variant_id, s.creative_id,
                       (SELECT COUNT(*) FROM events e
                        WHERE e.session_id = s.id AND e.tenant_id = s.tenant_id)            AS event_count,
                       s.converted,
                       (SELECT MAX((e.event_data->>'scroll_depth')::int) FROM events e
                        WHERE e.session_id = s.id AND e.tenant_id = s.tenant_id
                          AND e.event_type = 'SCROLL')                                    AS max_scroll_depth,
                       (SELECT EXISTS(
                           SELECT 1 FROM events e
                           WHERE e.session_id = s.id AND e.tenant_id = s.tenant_id AND e.event_type = 'FORM_START'
                       )) AS form_started,
                       lv.match_score AS variant_match_score,
                       (SELECT CASE a.status WHEN 'COMPLETED' THEN 1.0 WHEN 'NO_SHOW' THEN 0.0 END
                        FROM appointments a
                        WHERE a.session_id = s.id AND a.tenant_id = s.tenant_id
                        ORDER BY a.scheduled_at DESC LIMIT 1)                                  AS show_rate
                FROM sessions s
                LEFT JOIN landing_variants lv ON lv.id = s.variant_id
                WHERE s.last_event_at < now() - INTERVAL '30 minutes'   -- session closed
                  AND s.last_event_at >= ?
                  AND s.quality_score IS NULL
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                LIMIT 5000
                """, java.sql.Timestamp.from(cutoff));

        if (sessions.isEmpty()) return;
        log.debug("QualityScoreScheduler: scoring {} sessions", sessions.size());

        int updated = 0;
        for (Map<String, Object> s : sessions) {
            try {
                double score = computeScore(s);
                jdbc.update("""
                        UPDATE sessions SET quality_score = ? WHERE id = ?
                        """, score, s.get("id"));
                updated++;
            } catch (Exception ex) {
                log.warn("quality_score failed for session {}: {}", s.get("id"), ex.getMessage());
            }
        }
        log.info("QualityScoreScheduler: updated {} session quality scores", updated);

        refreshCreativeQuality();
    }

    private double computeScore(Map<String, Object> s) {
        Number matchScoreRaw = (Number) s.get("variant_match_score");
        double matchScore = matchScoreRaw != null ? matchScoreRaw.doubleValue() : 0.5;

        Number eventCount = (Number) s.get("event_count");
        boolean bounced = eventCount == null || eventCount.intValue() <= 1;

        Number scrollRaw = (Number) s.get("max_scroll_depth");
        double scroll = scrollRaw != null ? Math.min(1.0, scrollRaw.doubleValue() / 100.0) : 0.0;

        boolean converted = Boolean.TRUE.equals(s.get("converted"));
        boolean formStarted = Boolean.TRUE.equals(s.get("form_started"));
        double formCompletion = converted ? 1.0 : formStarted ? 0.3 : 0.0;

        // Roadmap 8-3: the appointment outcome (held / no-show) is the heaviest component when present
        Number showRaw = (Number) s.get("show_rate");
        Double showRate = showRaw != null ? showRaw.doubleValue() : null;

        return QualityScoreMath.score(matchScoreRaw != null ? matchScore : null, bounced, scroll, formCompletion, showRate);
    }

    private void refreshCreativeQuality() {
        // Upsert 30-day creative_quality aggregates for variants/creatives with new scores
        jdbc.update("""
                INSERT INTO creative_quality
                    (id, tenant_id, creative_id, variant_id, window_key,
                     quality_score, clickbait_suspect, components, computed_at)
                SELECT
                    gen_random_uuid(),
                    s.tenant_id,
                    s.creative_id,
                    NULL,
                    '30d',
                    AVG(s.quality_score),
                    false,
                    NULL,
                    now()
                FROM sessions s
                WHERE s.quality_score IS NOT NULL
                  AND s.last_event_at >= now() - INTERVAL '30 days'
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                  AND s.creative_id IS NOT NULL
                GROUP BY s.tenant_id, s.creative_id
                ON CONFLICT (tenant_id, creative_id, window_key) DO UPDATE
                    SET quality_score  = EXCLUDED.quality_score,
                        computed_at    = EXCLUDED.computed_at
                WHERE creative_quality.variant_id IS NULL
                """);
    }
}
