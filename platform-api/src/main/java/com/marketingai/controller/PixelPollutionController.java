package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pixel Pollution Auto-Detection API — Level 3 (vision §6.8).
 *
 * GET /api/v1/pixel-pollution/scans           — scan history for the tenant
 * GET /api/v1/pixel-pollution/recommendations — campaigns with active drift alerts
 *
 * Pause recommendations are informational: the marketer acts manually via
 * the campaign dashboard. Auto-pausing requires Marketing API write (Phase 3).
 */
@Slf4j
@RestController
@RequestMapping("/pixel-pollution")
@RequiredArgsConstructor
public class PixelPollutionController {

    private final JdbcTemplate jdbc;

    /** Scan history for the last N days (default 30). */
    @GetMapping("/scans")
    public List<Map<String, Object>> listScans(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "30") int days) {

        return jdbc.queryForList(
                """
                SELECT
                    id::text           AS id,
                    utm_campaign,
                    scan_date,
                    forms_14d,
                    meetings_14d,
                    quality_rate_14d,
                    baseline_rate_30d,
                    drift_pct,
                    alert_fired,
                    created_at
                FROM pixel_pollution_scans
                WHERE tenant_id = ?
                  AND scan_date >= CURRENT_DATE - ?::int
                ORDER BY scan_date DESC, alert_fired DESC
                LIMIT 200
                """,
                tenantId, days
        );
    }

    /**
     * Pause recommendations — campaigns whose quality rate drifted > 30% below baseline
     * in the last 14 days, with at least 20 form submissions.
     *
     * Each row includes the drift percentage and recommended action.
     * Marketer must manually pause the campaign; there is no auto-pause.
     */
    @GetMapping("/recommendations")
    public List<Map<String, Object>> listRecommendations(
            @RequestAttribute("tenantId") UUID tenantId) {

        return jdbc.queryForList(
                """
                SELECT
                    utm_campaign,
                    MAX(scan_date)          AS latest_scan,
                    MAX(forms_14d)          AS forms_14d,
                    MAX(meetings_14d)       AS meetings_14d,
                    MAX(quality_rate_14d)   AS quality_rate_14d,
                    MAX(baseline_rate_30d)  AS baseline_rate_30d,
                    MIN(drift_pct)          AS worst_drift_pct,
                    COUNT(*)                AS alert_days,
                    'PAUSE_CAMPAIGN'        AS recommended_action
                FROM pixel_pollution_scans
                WHERE tenant_id  = ?
                  AND alert_fired = true
                  AND scan_date  >= CURRENT_DATE - 14
                GROUP BY utm_campaign
                ORDER BY worst_drift_pct ASC
                """,
                tenantId
        );
    }
}
