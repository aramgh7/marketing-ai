package com.marketingai.scheduler;

import com.marketingai.service.TokenVault;
import com.marketingai.service.LearningPhaseService;
import com.marketingai.service.MetaApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-reads ad set learning_phase 24h after every Meta write (vision §6.5, §25 p.8).
 * Fills phase_after in learning_phase_log so the system knows whether an action
 * caused a learning reset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningPhaseCheckScheduler {

    private final JdbcTemplate jdbc;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final MetaApiService metaApi;
    private final LearningPhaseService learningPhaseService;

    /** Every 30 minutes: fill phase_after for entries older than 24h */
    @Scheduled(fixedDelay = 30 * 60 * 1000)
    public void checkPendingPhases() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT lpl.id, lpl.ad_set_id, t.meta_access_token_enc
                FROM learning_phase_log lpl
                JOIN tenants t ON t.id = lpl.tenant_id
                WHERE lpl.phase_after IS NULL
                  AND lpl.created_at < NOW() - INTERVAL '24 hours'
                LIMIT 100
                """);

        if (pending.isEmpty()) return;

        log.info("LearningPhaseCheck: {} pending entries to resolve", pending.size());

        for (Map<String, Object> row : pending) {
            UUID logId = (UUID) row.get("id");
            String adSetId = (String) row.get("ad_set_id");
            byte[] tokenBytes = (byte[]) row.get("meta_access_token_enc");

            if (tokenBytes == null || adSetId == null) {
                learningPhaseService.logPhaseAfter(logId, "UNKNOWN");
                continue;
            }

            String accessToken = vault.openOrNull(tokenBytes);
            if (accessToken == null) {
                learningPhaseService.logPhaseAfter(logId, "UNKNOWN");
                continue;
            }
            try {
                String phase = metaApi.getAdSetLearningPhase(accessToken, adSetId);
                learningPhaseService.logPhaseAfter(logId, phase != null ? phase : "UNKNOWN");
            } catch (Exception e) {
                log.warn("Could not fetch phase for adSetId={}: {}", adSetId, e.getMessage());
                learningPhaseService.logPhaseAfter(logId, "FETCH_ERROR");
            }
        }

        log.info("LearningPhaseCheck: resolved {} entries", pending.size());
    }
}
