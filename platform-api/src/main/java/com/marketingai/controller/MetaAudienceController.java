package com.marketingai.controller;

import com.marketingai.service.TokenVault;
import com.marketingai.service.MetaApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Meta Custom Audience Sync (Phase 5-5, §7.3 Этап 5).
 *
 * Pushes behavioural segments to Meta as custom audiences via CAPI.
 * Spring Boot is the ONLY CAPI sender (arch §12 #14).
 *
 * Custom audiences = hints to Advantage+ — NOT hard targeting (§25 п.12).
 * Only sessions with consent_level='full' contribute to audience lists.
 */
@Slf4j
@RestController
@RequestMapping("/meta/audiences")
@RequiredArgsConstructor
public class MetaAudienceController {

    private final JdbcTemplate   jdbc;
    private final MetaApiService metaApiService;
    private final TokenVault vault;   // roadmap 9-1: sealed secrets

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/meta/audiences — list sync history for tenant
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT segment_label, audience_id, session_count, synced_at
                FROM meta_audience_sync
                WHERE tenant_id = ?
                ORDER BY synced_at DESC
                LIMIT 100
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/meta/audiences/sync — push behavioural segments to Meta
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncAudiences(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody(required = false) Map<String, Object> body) {

        // Build segments from cluster labels with consent_level='full'
        List<Map<String, Object>> segments = jdbc.queryForList("""
                SELECT s.cluster_label AS segment_label,
                       COUNT(DISTINCT s.id) AS session_count
                FROM sessions s
                WHERE s.tenant_id = ?
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                  AND s.cluster_label IS NOT NULL
                  AND s.started_at > now() - interval '30 days'
                GROUP BY s.cluster_label
                HAVING COUNT(DISTINCT s.id) >= 100
                """, tenantId);

        if (segments.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "synced", 0,
                    "message", "No segments with ≥100 qualifying sessions"
            ));
        }

        // Retrieve Meta pixel credentials for this tenant
        Map<String, Object> pixelConfig;
        try {
            pixelConfig = jdbc.queryForMap("""
                    SELECT p.capi_access_token_enc AS token_enc,
                           p.pixel_id,
                           t.meta_ad_account_id AS ad_account_id
                    FROM meta_pixels p
                    JOIN tenants t ON t.id = p.tenant_id
                    WHERE p.tenant_id = ? AND p.status = 'ACTIVE' AND p.purpose = 'PRIMARY'
                    LIMIT 1
                    """, tenantId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "No active Meta pixel configured for this tenant");
        }

        String accessToken  = vault.openOrNull((byte[]) pixelConfig.get("token_enc"));
        String adAccountId  = (String) pixelConfig.get("ad_account_id");

        int syncedCount = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> seg : segments) {
            String segmentLabel = (String) seg.get("segment_label");
            int    sessionCount = ((Number) seg.get("session_count")).intValue();

            // Pull hashed emails/phones for this segment (only users who gave full consent)
            List<Map<String, Object>> contacts = jdbc.queryForList("""
                    SELECT DISTINCT
                        encode(sha256(LOWER(TRIM(u.email))::bytea), 'hex') AS hashed_email
                    FROM sessions s
                    JOIN users u ON u.id::text = s.crm_user_id
                    WHERE s.tenant_id = ?
                      AND s.cluster_label = ?
                      AND s.consent_level = 'full'
                      AND s.is_suspicious = false
                      AND s.started_at > now() - interval '30 days'
                      AND u.email IS NOT NULL
                    LIMIT 10000
                    """, tenantId, segmentLabel);

            if (contacts.isEmpty()) {
                continue;
            }

            List<String> hashedEmails = contacts.stream()
                    .map(c -> (String) c.get("hashed_email"))
                    .filter(Objects::nonNull)
                    .toList();

            try {
                // Create or update custom audience via Meta CAPI
                String audienceId = metaApiService.upsertCustomAudience(
                        accessToken,
                        adAccountId,
                        "mai_" + segmentLabel,
                        "Marketing AI behavioral segment: " + segmentLabel,
                        hashedEmails
                );

                // Record sync in meta_audience_sync
                jdbc.update("""
                        INSERT INTO meta_audience_sync
                            (tenant_id, segment_label, audience_id, session_count, synced_at)
                        VALUES (?, ?, ?, ?, now())
                        """, tenantId, segmentLabel, audienceId, sessionCount);

                results.add(Map.of(
                        "segment_label", segmentLabel,
                        "audience_id",   audienceId,
                        "session_count", sessionCount,
                        "contacts_sent", hashedEmails.size()
                ));
                syncedCount++;

                log.info("Meta audience sync: tenant={} segment={} audienceId={} contacts={}",
                        tenantId, segmentLabel, audienceId, hashedEmails.size());

            } catch (Exception e) {
                log.error("Failed to sync segment {} for tenant {}: {}",
                        segmentLabel, tenantId, e.getMessage());
                results.add(Map.of(
                        "segment_label", segmentLabel,
                        "error",         e.getMessage()
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "synced",  syncedCount,
                "results", results
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/meta/audiences/segments — preview which segments exist
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/segments")
    public ResponseEntity<List<Map<String, Object>>> segments(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cluster_label AS segment_label,
                       COUNT(DISTINCT id) AS session_count,
                       ROUND(AVG(CASE WHEN converted THEN 1.0 ELSE 0.0 END)::numeric, 4) AS cvr
                FROM sessions
                WHERE tenant_id = ?
                  AND consent_level = 'full'
                  AND is_suspicious = false
                  AND cluster_label IS NOT NULL
                  AND started_at > now() - interval '30 days'
                GROUP BY cluster_label
                ORDER BY session_count DESC
                """, tenantId);

        return ResponseEntity.ok(rows);
    }
}
