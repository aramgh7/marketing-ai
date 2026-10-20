package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.MetaApiService;
import com.marketingai.service.ModerationProbeMath;
import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moderation probe poll (roadmap 9-8; arch §16.17): every 30 min, PENDING {@code moderation_results}
 * rows of probe ad sets are checked against Meta (effective_status + ad_review_feedback).
 * APPROVED / REJECTED close the row; REJECTED alerts so the creative is reworked before any spend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationProbeScheduler {

    private final JdbcTemplate jdbc;
    private final MetaApiService metaApi;
    private final TokenVault vault;
    private final AlertService alertService;

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void poll() {
        List<Map<String, Object>> pending = jdbc.queryForList("""
                SELECT mr.id, mr.tenant_id, mr.creative_id, a.meta_ad_id, a.meta_ad_set_id, t.meta_access_token_enc
                FROM moderation_results mr
                JOIN meta_ad_sets a ON a.id = mr.ad_set_id
                JOIN tenants t ON t.id = mr.tenant_id
                WHERE mr.result = 'PENDING' AND mr.probe_type = 'meta_review' AND a.moderation_probe = TRUE
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
        String objectId = row.get("meta_ad_id") != null ? (String) row.get("meta_ad_id") : (String) row.get("meta_ad_set_id");
        if (objectId == null) return false;

        Map<String, String> status = metaApi.getAdReviewStatus(token, objectId);
        if (status.isEmpty()) return false;
        String effective = status.get("effective_status");
        String feedback = status.getOrDefault("ad_review_feedback", "");
        boolean hasFeedback = feedback != null && !feedback.isBlank() && !"{}".equals(feedback.trim());
        String verdict = ModerationProbeMath.verdict(effective, hasFeedback);
        if ("PENDING".equals(verdict)) {
            jdbc.update("UPDATE moderation_results SET checked_at = now(), meta_ad_id = ? WHERE id = ?", objectId, row.get("id"));
            return false;
        }
        String code = "REJECTED".equals(verdict) ? ModerationProbeMath.rejectionCode(effective, feedback) : null;
        jdbc.update("""
                UPDATE moderation_results
                SET result = ?, meta_rejection_code = ?, feedback = ?::jsonb, meta_ad_id = ?,
                    checked_at = now(), resolved_at = now()
                WHERE id = ?
                """, verdict, code, hasFeedback ? feedback : null, objectId, row.get("id"));
        if ("REJECTED".equals(verdict)) {
            alertService.sendAlert("🚫 Moderation probe REJECTED by Meta for creative " + row.get("creative_id")
                    + " (" + code + "). Rework the creative before launching the real batch.");
        }
        return true;
    }
}
