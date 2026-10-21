package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap Phase 11-4: "linkage health" — every ACTIVE Meta ad must be mapped to one of our creatives and carry
 * the platform's url_tags (mai_c={{ad.id}}), and no mapped ad may have drifted (URL / tags / asset changed by
 * hand). While it is red, test launches are refused by {@link MetaWriteGate} and the onboarding checklist shows it.
 */
@Service
@RequiredArgsConstructor
public class LinkageHealthService {

    public static final String TAG_MARKER = "mai_c=";

    public record Health(boolean healthy, int activeAds, int unmanaged, int missingTags, int drift) {
        public String summary() {
            return activeAds + " active Meta ads, " + unmanaged + " unmapped, " + missingTags + " without url_tags, " + drift + " drifted";
        }
    }

    private final JdbcTemplate jdbc;

    public Health check(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, creative_id, url_tags
                FROM meta_ad_map
                WHERE tenant_id = ? AND status IN ('ACTIVE', 'UNMANAGED', 'DRIFT')
                """, tenantId);
        return evaluate(rows);
    }

    /** Pure part, testable without a DB: rows are {status, creative_id, url_tags}. */
    public static Health evaluate(List<Map<String, Object>> rows) {
        int active = 0, unmanaged = 0, missingTags = 0, drift = 0;
        for (Map<String, Object> r : rows) {
            String status = String.valueOf(r.get("status"));
            if ("DRIFT".equals(status)) { drift++; active++; continue; }
            if ("UNMANAGED".equals(status)) { unmanaged++; active++; continue; }
            active++;
            if (r.get("creative_id") == null) unmanaged++;
            Object tags = r.get("url_tags");
            if (tags == null || !String.valueOf(tags).contains(TAG_MARKER)) missingTags++;
        }
        boolean healthy = unmanaged == 0 && missingTags == 0 && drift == 0;
        return new Health(healthy, active, unmanaged, missingTags, drift);
    }
}
