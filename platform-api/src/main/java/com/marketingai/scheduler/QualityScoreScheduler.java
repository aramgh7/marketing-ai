package com.marketingai.scheduler;

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

    @Scheduled(fixedDelay = 900_000) // every 15 minutes
    public void computeQualityScores() {
        Instant cutoff = Instant.now().minus(16, ChronoUnit.MINUTES);

        // Sessions closed in the last 16 min that don't yet have a quality_score
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT s.id, s.tenant_id, s.variant_id, s.creative_id,
                       s.event_count, s.converted, s.max_scroll_depth,
                       (SELECT EXISTS(
                           SELECT 1 FROM events e
                           WHERE e.session_id = s.id AND e.event_type = 'FORM_START'
                       )) AS form_started,
                       lv.match_score AS variant_match_score
                FROM sessions s
                LEFT JOIN landing_variants lv ON lv.id = s.variant_id
                WHERE s.closed_at IS NOT NULL
                  AND s.closed_at >= ?
                  AND s.quality_score IS NULL
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                LIMIT 5000
                """, cutoff);

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

        double score = 0.25 * matchScore
                     + 0.25 * (bounced ? 0.0 : 1.0)
                     + 0.25 * scroll
                     + 0.25 * formCompletion;
        return Math.round(Math.max(0.0, Math.min(1.0, score)) * 10000.0) / 10000.0;
    }

    private void refreshCreativeQuality() {
        // Upsert 30-day creative_quality aggregates for variants/creatives with new scores
        jdbc.update("""
                INSERT INTO creative_quality
                    (id, tenant_id, creative_id, variant_id, window,
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
                  AND s.closed_at >= now() - INTERVAL '30 days'
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                  AND s.creative_id IS NOT NULL
                GROUP BY s.tenant_id, s.creative_id
                ON CONFLICT DO NOTHING
                """);
    }
}
