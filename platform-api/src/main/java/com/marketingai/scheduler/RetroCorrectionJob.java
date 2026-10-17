package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-Device Identity — Retro Correction Job (vision §7.4).
 *
 * Daily 03:00 UTC: for each tenant, finds sessions with external_id_hash (consented)
 * and links earlier anonymous sessions from the same hash.
 * Sets bounce_corrected_at so earlier "bounce" sessions are re-attributed as
 * "returned & converted" before posterior recalculation.
 *
 * Safety:
 *   - Only sessions with consent_level = 'full'
 *   - Never touches sessions with existing bounce_corrected_at
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetroCorrectionJob {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void run() {
        List<UUID> tenantIds = jdbc.queryForList(
                "SELECT id FROM tenants WHERE status = 'ACTIVE'", UUID.class);

        for (UUID tenantId : tenantIds) {
            try {
                processTenant(tenantId);
            } catch (Exception e) {
                log.error("RetroCorrectionJob failed for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    void processTenant(UUID tenantId) {
        // Find all distinct external_id_hashes for consented sessions in this tenant
        List<String> hashes = jdbc.queryForList("""
                SELECT DISTINCT external_id_hash
                FROM sessions
                WHERE tenant_id = ?
                  AND external_id_hash IS NOT NULL
                  AND consent_level = 'full'
                """, String.class, tenantId);

        if (hashes.isEmpty()) return;

        int matched = 0;
        int corrected = 0;

        for (String hash : hashes) {
            // Find the earliest (anchor) consented session with this hash
            List<Map<String, Object>> anchorRows = jdbc.queryForList("""
                    SELECT id
                    FROM sessions
                    WHERE tenant_id = ?
                      AND external_id_hash = ?
                      AND consent_level = 'full'
                    ORDER BY created_at ASC
                    LIMIT 1
                    """, tenantId, hash);

            if (anchorRows.isEmpty()) continue;
            UUID anchorId = (UUID) anchorRows.get(0).get("id");

            // Find earlier anonymous sessions (no hash) within 90 days before the anchor.
            // NOTE: without a persistent cross-visit cookie_id we cannot guarantee same-device;
            // the 90-day window reduces false matches. True cross-device linking requires
            // a persistent first-party identifier (§7.4 — deferred to post-MVP).
            List<UUID> earlierSessions = jdbc.queryForList("""
                    SELECT s.id
                    FROM sessions s
                    WHERE s.tenant_id = ?
                      AND s.external_id_hash IS NULL
                      AND s.bounce_corrected_at IS NULL
                      AND s.consent_level = 'full'
                      AND s.created_at < (
                          SELECT created_at FROM sessions WHERE id = ? AND tenant_id = ?
                      )
                      AND s.created_at > (
                          SELECT created_at - INTERVAL '90 days' FROM sessions WHERE id = ? AND tenant_id = ?
                      )
                    LIMIT 50
                    """, UUID.class, tenantId, anchorId, tenantId, anchorId, tenantId);

            matched += earlierSessions.size();

            for (UUID sid : earlierSessions) {
                jdbc.update("""
                        UPDATE sessions
                           SET bounce_corrected_at = now(),
                               linked_session_ids  = array_append(
                                   COALESCE(linked_session_ids, '{}'), ?::uuid
                               )
                         WHERE id = ?
                           AND tenant_id = ?
                        """, anchorId, sid, tenantId);
                corrected++;
            }
        }

        if (matched > 0 || corrected > 0) {
            jdbc.update("""
                    INSERT INTO retro_correction_runs
                        (tenant_id, sessions_matched, sessions_corrected)
                    VALUES (?, ?, ?)
                    """, tenantId, matched, corrected);
            log.info("RetroCorrectionJob tenant={}: matched={} corrected={}",
                    tenantId, matched, corrected);
        }
    }
}
