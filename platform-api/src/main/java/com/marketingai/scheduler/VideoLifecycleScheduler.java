package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Video asset lifecycle management (vision §23 Phase 4).
 *
 * Rules:
 *   - HOT footage older than 90 days → COLD (archived in object storage)
 *   - Renders whose footage is COLD and were not approved → marked killed after 30 days
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VideoLifecycleScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 2 * * *")
    public void runLifecycleScan() {
        archiveOldFootage();
        killOrphanedRenders();
    }

    private void archiveOldFootage() {
        int moved = jdbc.update("""
                UPDATE video_footage
                   SET asset_tier  = 'COLD',
                       archived_at = now()
                 WHERE asset_tier  = 'HOT'
                   AND uploaded_at < now() - interval '90 days'
                """);
        if (moved > 0) {
            log.info("VideoLifecycle: moved {} footage files HOT → COLD", moved);
        }
    }

    // Renders that are PENDING/RENDERING and still have no storage_url after 30 days
    // (never completed — mark as FAILED to keep DB clean)
    private void killOrphanedRenders() {
        int killed = jdbc.update("""
                UPDATE video_renders
                   SET killed_at = now(),
                       status    = 'FAILED'
                 WHERE killed_at  IS NULL
                   AND status    NOT IN ('COMPLETED')
                   AND created_at < now() - interval '30 days'
                """);
        if (killed > 0) {
            log.info("VideoLifecycle: killed {} stale renders (>30d, no output)", killed);
        }
    }
}
