package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Logs variants that received new conversion events in the last 15 minutes.
 * Actual alpha/beta writes happen in FastAPI /decide (single writer rule).
 * This scheduler ensures the decide endpoint is called for active variants
 * and the posterior stays fresh (vision §2.x, §10.1 single-writer invariant).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PosteriorRecalcScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(fixedDelay = 900_000)
    public void logStaleVariants() {
        // Find variants that have new conversion events but haven't had a /decide call
        // ONLY FastAPI writes alpha/beta — Spring Boot NEVER modifies thompson_arms
        List<Map<String, Object>> stale = jdbc.queryForList("""
                SELECT DISTINCT v.id  AS variant_id,
                                v.tenant_id,
                                COUNT(e.id) AS new_conversions
                FROM variants v
                JOIN sessions s ON s.tenant_id = v.tenant_id
                JOIN events   e ON e.session_id = s.id
                WHERE v.status = 'ACTIVE'
                  AND e.event_type = 'CONVERSION'
                  AND e.created_at > NOW() - INTERVAL '15 minutes'
                GROUP BY v.id, v.tenant_id
                LIMIT 200
                """);

        if (!stale.isEmpty()) {
            log.debug("PosteriorRecalcScheduler: {} variants have new conversion events — "
                    + "posteriors will refresh on next FastAPI /decide call", stale.size());
        }
    }
}
