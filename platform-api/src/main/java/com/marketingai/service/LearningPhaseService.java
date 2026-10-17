package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Logs learning phase before/after every Meta write (vision §6.5, §25 p.8).
 * phase_after is filled by LearningPhaseCheckScheduler 24h after the action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPhaseService {

    private final JdbcTemplate jdbc;

    /**
     * Record learning phase before a Meta action.
     * @return the log row UUID (pass to logPhaseAfter when updating)
     */
    public UUID logBeforeAction(UUID tenantId, String adSetId, String action, String phaseBefore) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO learning_phase_log
                    (id, tenant_id, ad_set_id, action, phase_before)
                VALUES (?, ?, ?, ?, ?)
                """, id, tenantId, adSetId, action, phaseBefore);
        log.debug("LearningPhase logged: tenant={} adSet={} action={} before={}",
                tenantId, adSetId, action, phaseBefore);
        return id;
    }

    /**
     * Fill in phase_after once the result is known (called 24h later by scheduler).
     */
    public void logPhaseAfter(UUID logId, String phaseAfter) {
        int rows = jdbc.update("""
                UPDATE learning_phase_log
                   SET phase_after = ?, phase_checked_at = now()
                 WHERE id = ?
                """, phaseAfter, logId);
        if (rows > 0) {
            log.debug("LearningPhase updated: logId={} phaseAfter={}", logId, phaseAfter);
        }
    }
}
