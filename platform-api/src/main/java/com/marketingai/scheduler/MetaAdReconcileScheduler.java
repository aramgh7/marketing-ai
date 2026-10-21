package com.marketingai.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.service.AdReconcileMath;
import com.marketingai.service.AlertService;
import com.marketingai.service.MetaApiService;
import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Roadmap Phase 11-4: hourly read-back of every ad account. Ads the platform published are checked for drift
 * (URL / url_tags / asset changed by hand); ads created outside the platform are matched by the creative code
 * in their name or recorded as UNMANAGED (outside learning) with one alert. Read-only: works at stage READ_ONLY.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaAdReconcileScheduler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MetaApiService metaApi;
    private final TokenVault vault;
    private final AlertService alertService;

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void reconcileAll() {
        List<Map<String, Object>> tenants = jdbc.queryForList("""
                SELECT id, meta_ad_account_id, meta_access_token_enc
                FROM tenants
                WHERE meta_access_token_enc IS NOT NULL AND meta_ad_account_id IS NOT NULL
                """);
        for (Map<String, Object> t : tenants) {
            UUID tenantId = (UUID) t.get("id");
            try {
                String token = vault.openOrNull((byte[]) t.get("meta_access_token_enc"));
                if (token == null) continue;
                List<Map<String, String>> ads = metaApi.listAds(token, (String) t.get("meta_ad_account_id"));
                int[] counts = reconcileTenant(tenantId, ads);
                log.info("MetaAdReconcile tenant={} ads={} matched={} unmanaged={} drift={}", tenantId, ads.size(), counts[0], counts[1], counts[2]);
            } catch (Exception e) {
                log.warn("MetaAdReconcile tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Package-private for tests. Returns {matched, unmanaged, drift}. */
    int[] reconcileTenant(UUID tenantId, List<Map<String, String>> ads) {
        int matched = 0, unmanaged = 0, drifted = 0;
        Set<String> seen = new HashSet<>();
        for (Map<String, String> ad : ads) {
            String adId = ad.get("id");
            if (adId == null || adId.isBlank()) continue;
            seen.add(adId);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, creative_id, source, status, destination_url, url_tags, meta_video_id, meta_image_hash, drift
                    FROM meta_ad_map WHERE tenant_id = ? AND meta_ad_id = ?
                    """, tenantId, adId);
            String metaStatus = AdReconcileMath.statusFor(ad.get("effective_status"));
            if (rows.isEmpty()) {
                String code = AdReconcileMath.creativeCodeIn(ad.get("name"));
                UUID creativeId = code == null ? null : creativeByCode(tenantId, code);
                String status = creativeId == null ? "UNMANAGED" : metaStatus;
                jdbc.update("""
                        INSERT INTO meta_ad_map
                            (tenant_id, meta_ad_id, meta_adset_id, meta_campaign_id, meta_creative_id, meta_video_id, meta_image_hash,
                             creative_id, destination_url, url_tags, ad_name, source, status, last_seen_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECONCILED', ?, now())
                        ON CONFLICT (tenant_id, meta_ad_id) DO NOTHING
                        """, tenantId, adId, blankToNull(ad.get("adset_id")), blankToNull(ad.get("campaign_id")),
                        blankToNull(ad.get("creative_id")), blankToNull(ad.get("video_id")), blankToNull(ad.get("image_hash")),
                        creativeId, blankToNull(ad.get("link_url")), blankToNull(ad.get("url_tags")), ad.get("name"), status);
                if (creativeId != null) {
                    matched++;
                    setPublishStatus(tenantId, creativeId, AdReconcileMath.publishStatusFor(status));
                } else {
                    unmanaged++;
                    if (!"ARCHIVED".equals(metaStatus)) {
                        alertService.sendAlert("⚠️ Unmanaged Meta ad \"" + ad.get("name") + "\" (" + adId
                                + ") — created outside the platform. Link it to a creative on the Meta ads page or it stays outside learning.");
                    }
                }
                continue;
            }
            Map<String, Object> row = rows.get(0);
            UUID mapId = (UUID) row.get("id");
            String status = (String) row.get("status");
            UUID creativeId = (UUID) row.get("creative_id");

            // Drift check for rows the platform wrote (expected values known).
            Map<String, Map<String, String>> drift = AdReconcileMath.drift(
                    (String) row.get("destination_url"), ad.get("link_url"),
                    (String) row.get("url_tags"), ad.get("url_tags"),
                    (String) row.get("meta_video_id"), ad.get("video_id"),
                    (String) row.get("meta_image_hash"), ad.get("image_hash"));
            boolean managed = "MANAGED".equals(row.get("source")) || "MANUAL".equals(row.get("source"));
            if (managed && !drift.isEmpty() && !"ARCHIVED".equals(metaStatus)) {
                boolean firstTime = row.get("drift") == null;
                jdbc.update("""
                        UPDATE meta_ad_map
                        SET drift = ?::jsonb, drift_detected_at = COALESCE(drift_detected_at, now()), status = 'DRIFT',
                            last_seen_at = now(), updated_at = now()
                        WHERE id = ?
                        """, toJson(drift), mapId);
                if (creativeId != null) setPublishStatus(tenantId, creativeId, "DRIFT");
                drifted++;
                if (firstTime) {
                    alertService.sendAlert("⚠️ Meta ad " + adId + " drifted from what the platform published (" + drift.keySet()
                            + ") — the pair creative → landing is frozen until you acknowledge it on the Meta ads page.");
                }
                continue;
            }

            String newStatus = status;
            if ("DRIFT".equals(status) && drift.isEmpty()) newStatus = metaStatus;        // fixed by hand in Ads Manager
            else if (!AdReconcileMath.moderationOwned(status) && !"DRIFT".equals(status)) newStatus = metaStatus;
            else if (AdReconcileMath.moderationOwned(status) && "ARCHIVED".equals(metaStatus)) newStatus = "ARCHIVED";
            if ("UNKNOWN".equals(newStatus)) newStatus = status;
            if (creativeId == null && "UNMANAGED".equals(status)) {
                unmanaged++;
                newStatus = "ARCHIVED".equals(metaStatus) ? "ARCHIVED" : "UNMANAGED";
            }
            jdbc.update("""
                    UPDATE meta_ad_map
                    SET status = ?, last_seen_at = now(), updated_at = now(),
                        drift = CASE WHEN ? THEN NULL ELSE drift END,
                        drift_detected_at = CASE WHEN ? THEN NULL ELSE drift_detected_at END
                    WHERE id = ?
                    """, newStatus, "DRIFT".equals(status) && drift.isEmpty(), "DRIFT".equals(status) && drift.isEmpty(), mapId);
            if (creativeId != null) {
                matched++;
                if (!newStatus.equals(status)) setPublishStatus(tenantId, creativeId, AdReconcileMath.publishStatusFor(newStatus));
            }
        }
        // Ads that disappeared from the account.
        List<Map<String, Object>> live = jdbc.queryForList("""
                SELECT id, meta_ad_id, creative_id FROM meta_ad_map
                WHERE tenant_id = ? AND status IN ('ACTIVE', 'PAUSED', 'UNMANAGED', 'DRIFT')
                """, tenantId);
        for (Map<String, Object> r : live) {
            if (seen.contains((String) r.get("meta_ad_id"))) continue;
            jdbc.update("UPDATE meta_ad_map SET status = 'ARCHIVED', updated_at = now() WHERE id = ?", r.get("id"));
            if (r.get("creative_id") != null) setPublishStatus(tenantId, (UUID) r.get("creative_id"), "NOT_IN_META");
        }
        return new int[]{matched, unmanaged, drifted};
    }

    private UUID creativeByCode(UUID tenantId, String code) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM creatives WHERE tenant_id = ? AND creative_code = ? LIMIT 1", UUID.class, tenantId, code);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void setPublishStatus(UUID tenantId, UUID creativeId, String publishStatus) {
        // A creative with several ads keeps the "strongest" state: ACTIVE wins over PAUSED; DRIFT/REJECTED always surface.
        jdbc.update("""
                UPDATE creatives SET publish_status = ?
                WHERE id = ? AND tenant_id = ?
                  AND NOT (publish_status = 'ACTIVE_IN_META' AND ? IN ('PAUSED_IN_META', 'NOT_IN_META'))
                """, publishStatus, creativeId, tenantId, publishStatus);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
