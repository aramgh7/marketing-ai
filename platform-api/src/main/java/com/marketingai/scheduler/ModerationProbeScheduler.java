package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.MetaApiService;
import com.marketingai.service.ModerationProbeMath;
import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation probe poll (roadmap 9-8; arch §16.17): every 30 min, PENDING {@code moderation_results}
 * rows of probe ad sets are checked against Meta (effective_status + ad_review_feedback).
 * APPROVED / REJECTED close the row; REJECTED alerts so the creative is reworked before any spend.
 * Audit M-3: a quiet status counts as APPROVED only after {@code app.meta.moderation-min-age-hours} (default 6)
 * since the ad row was created — see {@link ModerationProbeMath}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationProbeScheduler {

    private final JdbcTemplate jdbc;
    private final MetaApiService metaApi;
    private final TokenVault vault;
    private final AlertService alertService;
    private final TransactionTemplate tx;

    @Value("${app.meta.moderation-min-age-hours:6}")
    private long moderationMinAgeHours = 6;

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void poll() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT mr.id, mr.tenant_id, mr.creative_id, mr.meta_ad_id AS probe_ad_id, a.meta_ad_id, a.meta_ad_set_id,
                       t.meta_access_token_enc, COALESCE(mm.created_at, a.created_at) AS published_at
                FROM moderation_results mr
                LEFT JOIN meta_ad_sets a ON a.id = mr.ad_set_id
                LEFT JOIN meta_ad_map mm ON mm.tenant_id = mr.tenant_id AND mm.meta_ad_id = mr.meta_ad_id
                JOIN tenants t ON t.id = mr.tenant_id
                WHERE mr.result = 'PENDING' AND mr.probe_type = 'meta_review'
                  AND (a.moderation_probe = TRUE OR mr.meta_ad_id IS NOT NULL)
                ORDER BY mr.checked_at
                LIMIT 200
                """);
        int resolved = 0;
        for (Map<String, Object> row : pending) {
            try {
                if (pollOne(row)) resolved++;
            } catch (Exception e) {
                log.warn("ModerationProbe row={} failed: {}", row.get("id"), e.getMessage());
            }
        }
        if (!pending.isEmpty()) log.info("ModerationProbeScheduler: {} pending, {} resolved", pending.size(), resolved);
    }

    /** Package-private for tests. True when the row left PENDING. */
    boolean pollOne(Map<String, Object> row) {
        String token = vault.openOrNull((byte[]) row.get("meta_access_token_enc"));
        if (token == null) return false;
        // Roadmap Phase 11: an ad published from the cabinet carries its own ad id on the probe row.
        String objectId = row.get("probe_ad_id") != null ? (String) row.get("probe_ad_id")
                : row.get("meta_ad_id") != null ? (String) row.get("meta_ad_id") : (String) row.get("meta_ad_set_id");
        if (objectId == null) return false;

        Map<String, String> status = metaApi.getAdReviewStatus(token, objectId);
        if (status.isEmpty()) return false;
        String effective = status.get("effective_status");
        String feedback = status.getOrDefault("ad_review_feedback", "");
        boolean hasFeedback = feedback != null && !feedback.isBlank() && !"{}".equals(feedback.trim());
        Duration age = ModerationProbeMath.ageOf(row.get("published_at"), Instant.now());
        String verdict = ModerationProbeMath.verdict(effective, hasFeedback, age, Duration.ofHours(moderationMinAgeHours));
        if ("PENDING".equals(verdict)) {
            jdbc.update("UPDATE moderation_results SET checked_at = now(), meta_ad_id = ? WHERE id = ?", objectId, row.get("id"));
            return false;
        }
        String code = "REJECTED".equals(verdict) ? ModerationProbeMath.rejectionCode(effective, feedback) : null;
        UUID tenantId = (UUID) row.get("tenant_id");
        UUID creativeId = (UUID) row.get("creative_id");
        // Audit D-22: the probe row, the ad map and the creative's publish status move together or not at all.
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE moderation_results
                    SET result = ?, meta_rejection_code = ?, feedback = ?::jsonb, meta_ad_id = ?,
                        checked_at = now(), resolved_at = now()
                    WHERE id = ?
                    """, verdict, code, hasFeedback ? feedback : null, objectId, row.get("id"));
            // Roadmap Phase 11: propagate the verdict to the ad map and the creative's publish status.
            if (row.get("probe_ad_id") != null) {
                String mapStatus = "REJECTED".equals(verdict) ? "REJECTED" : "APPROVED";
                jdbc.update("UPDATE meta_ad_map SET status = ?, updated_at = now() WHERE tenant_id = ? AND meta_ad_id = ?",
                        mapStatus, tenantId, objectId);
                if (creativeId != null) {
                    jdbc.update("UPDATE creatives SET publish_status = ? WHERE id = ? AND tenant_id = ?",
                            "REJECTED".equals(verdict) ? "REJECTED" : "APPROVED_PAUSED", creativeId, tenantId);
                }
            }
        });
        if ("REJECTED".equals(verdict)) {
            alertService.sendAlert("🚫 Moderation probe REJECTED by Meta for creative " + row.get("creative_id")
                    + " (" + code + "). Rework the creative before launching the real batch.");
            if (creativeId != null) recordBlockedPostmortem(tenantId, creativeId, code, hasFeedback ? feedback : null);
        }
        return true;
    }

    /**
     * Roadmap Phase 11 / 8-17: a rejected published ad sends the creative to the graveyard as 'blocked_by_meta'
     * so BlockedCreativeRecoveryScheduler asks ML for a recovery brief with the blocked phrases as avoid-list.
     */
    void recordBlockedPostmortem(UUID tenantId, UUID creativeId, String code, String feedbackJson) {
        try {
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM creative_postmortems WHERE tenant_id = ? AND creative_id = ? AND failure_mode = 'blocked_by_meta'",
                    Integer.class, tenantId, creativeId);
            if (existing != null && existing > 0) return;
            String reason = code == null ? "rejected by Meta" : code;
            String analysis = "{\"reason\":" + quote(reason) + ",\"meta_feedback\":" + (feedbackJson == null ? "null" : feedbackJson) + "}";
            jdbc.update("""
                    INSERT INTO creative_postmortems
                        (tenant_id, creative_id, failure_mode, cvr, benchmark_cvr, sessions, analysis, postmortem_md)
                    VALUES (?, ?, 'blocked_by_meta', 0, 0.02, 0, ?::jsonb, ?)
                    """, tenantId, creativeId, analysis, "Rejected by Meta moderation: " + reason);
        } catch (Exception e) {
            log.warn("blocked_by_meta postmortem for creative {} skipped: {}", creativeId, e.getMessage());
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
