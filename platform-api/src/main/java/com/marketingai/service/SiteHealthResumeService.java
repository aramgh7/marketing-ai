package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resume protocol after a site-health incident (roadmap 3-13; vision §11.4, §22):
 * ad sets paused by the P0 autopause and a paused form are re-enabled only when
 * <b>three consecutive passes</b> have been observed <b>and</b> a marketer has confirmed.
 *
 * Pure decision in {@link Policy}; everything else is bookkeeping on site_health_incidents.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SiteHealthResumeService {

    public static final int REQUIRED_PASSES = 3;

    private final JdbcTemplate jdbc;
    private final MetaApiClient metaApiClient;
    private final AlertService alertService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pure: when may an incident be resumed, and when should the marketer be asked. */
    public static final class Policy {
        private Policy() {}

        public static boolean canResume(int consecutivePasses, Instant confirmedAt) {
            return consecutivePasses >= REQUIRED_PASSES && confirmedAt != null;
        }

        /** Ask once: passes reached the threshold, no confirmation yet, alert not yet sent. */
        public static boolean shouldAskForConfirmation(int consecutivePasses, Instant confirmedAt, Instant alertedAt) {
            return consecutivePasses >= REQUIRED_PASSES && confirmedAt == null && alertedAt == null;
        }

        /** A FAIL resets the pass counter — the three passes must be consecutive. */
        public static int nextPassCount(int current, boolean passed) {
            return passed ? current + 1 : 0;
        }
    }

    /** Record a check result against the open incident of the variant; resumes when policy allows. */
    public void recordCheck(UUID tenantId, UUID variantId, boolean passed) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                SELECT id, consecutive_passes, resume_confirmed_at, resume_alerted_at, cause
                FROM site_health_incidents
                WHERE tenant_id = ? AND variant_id = ? AND resolved_at IS NULL
                ORDER BY opened_at DESC LIMIT 1
                """, tenantId, variantId);
        if (open.isEmpty()) return;
        Map<String, Object> inc = open.get(0);
        UUID incidentId = (UUID) inc.get("id");
        int passes = Policy.nextPassCount(((Number) inc.get("consecutive_passes")).intValue(), passed);
        jdbc.update("UPDATE site_health_incidents SET consecutive_passes = ? WHERE id = ?", passes, incidentId);

        Instant confirmed = toInstant(inc.get("resume_confirmed_at"));
        Instant alerted = toInstant(inc.get("resume_alerted_at"));
        if (Policy.shouldAskForConfirmation(passes, confirmed, alerted)) {
            jdbc.update("UPDATE site_health_incidents SET resume_alerted_at = now() WHERE id = ?", incidentId);
            alertService.sendAlert("🟡 Site Health: landing " + variantId + " passed " + passes
                    + " consecutive checks after incident " + incidentId + " (" + inc.get("cause")
                    + "). Confirm resume: POST /api/v1/site-health/" + incidentId + "/confirm-resume");
        }
        if (Policy.canResume(passes, confirmed)) {
            resume(tenantId, incidentId, variantId);
        }
    }

    /** Marketer confirmation; resumes immediately when the passes are already there. */
    public boolean confirm(UUID tenantId, UUID incidentId, UUID userId) {
        int n = jdbc.update("""
                UPDATE site_health_incidents SET resume_confirmed_at = now(), resume_confirmed_by = ?
                WHERE id = ? AND tenant_id = ? AND resolved_at IS NULL
                """, userId, incidentId, tenantId);
        if (n == 0) return false;
        Map<String, Object> inc = jdbc.queryForMap(
                "SELECT variant_id, consecutive_passes FROM site_health_incidents WHERE id = ?", incidentId);
        if (Policy.canResume(((Number) inc.get("consecutive_passes")).intValue(), Instant.now())) {
            resume(tenantId, incidentId, (UUID) inc.get("variant_id"));
        }
        return true;
    }

    /** Re-enable what the incident paused: the form and the ad sets recorded in detail.paused_ad_set_ids. */
    @SuppressWarnings("unchecked")
    private void resume(UUID tenantId, UUID incidentId, UUID variantId) {
        Map<String, Object> inc = jdbc.queryForMap("SELECT detail FROM site_health_incidents WHERE id = ?", incidentId);
        List<Object> pausedIds = (List<Object>) Jsonb.asMap(inc.get("detail")).getOrDefault("paused_ad_set_ids", List.of());
        if (variantId != null) {
            jdbc.update("UPDATE landing_variants SET form_paused = FALSE, consecutive_form_passes = 0 WHERE id = ? AND tenant_id = ?",
                    variantId, tenantId);
        }
        for (Object id : pausedIds) {
            String adSetId = String.valueOf(id);
            metaApiClient.enqueue("P1", adSetId, tenantId, "RESUME_AFTER_INCIDENT", () ->
                    jdbc.update("UPDATE meta_ad_sets SET status = 'ACTIVE', synced_at = now() WHERE id = ?::uuid AND tenant_id = ? AND status = 'PAUSED'",
                            adSetId, tenantId));
        }
        jdbc.update("UPDATE site_health_incidents SET resolved_at = now(), resumed_at = now() WHERE id = ?", incidentId);
        alertService.sendAlert("🟢 Site Health: incident " + incidentId + " resolved — form re-enabled, "
                + pausedIds.size() + " ad set(s) queued for resume.");
        log.info("Site health incident {} resumed: {} ad sets", incidentId, pausedIds.size());
    }

    /** Called at autopause time so resume knows exactly which ad sets to re-enable. */
    public void rememberPausedAdSets(UUID incidentId, List<UUID> adSetIds) {
        try {
            jdbc.update("UPDATE site_health_incidents SET detail = COALESCE(detail, '{}'::jsonb) || ?::jsonb WHERE id = ?",
                    MAPPER.writeValueAsString(Map.of("paused_ad_set_ids", adSetIds.stream().map(UUID::toString).toList())),
                    incidentId);
        } catch (Exception e) {
            log.warn("could not record paused ad sets on incident {}: {}", incidentId, e.getMessage());
        }
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        return null;
    }
}
