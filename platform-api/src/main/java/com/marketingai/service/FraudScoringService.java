package com.marketingai.service;

import com.marketingai.dto.event.EventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 0-6: Behavioral fraud scoring.
 *
 * Two execution paths:
 *  - scoreInline()  called during event ingestion (fast, checks current batch)
 *  - reviewRecent() called hourly by FraudReviewScheduler (session-level retrospective)
 *
 * Suspicious sessions are excluded from Thompson Sampling training data (§12.6.3).
 * They remain in the denominator for profit/impression to preserve klikbeit signal (§15.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudScoringService {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;

    // Bot UA fragments (lowercase). Meta crawler is explicitly allowed.
    private static final List<String> BOT_UA_DENY = List.of(
            "googlebot", "bingbot", "slurp", "duckduckbot", "baiduspider",
            "yandexbot", "sogou", "semrushbot", "ahrefsbot", "mj12bot",
            "dotbot", "rogerbot", "linkedinbot", "twitterbot", "applebot",
            "petalbot", "bytespider", "gptbot", "chatgpt-user", "ccbot",
            "scrapy", "python-requests", "java/", "curl/", "wget/",
            "headlesschrome", "phantomjs", "selenium", "webdriver"
    );

    private static final List<String> BOT_UA_ALLOW = List.of(
            "facebookexternalhit", // Meta crawler — must NOT be blocked
            "googleads"            // Google Ads preview tool
    );

    // IP+UA combination threshold per hour (vision §12.6.2 Layer 2)
    private static final int IP_UA_CLUSTER_THRESHOLD = 50;

    // Cloudflare Bot Score < threshold → bot-like (0-99, lower = more bot)
    private static final int CF_BOT_SCORE_THRESHOLD = 30;

    // Fraud rate above this triggers a Telegram alert
    private static final double ALERT_FRAUD_RATE = 0.10;

    // Minimum sessions before alerting (avoid noise on low-traffic tenants)
    private static final int MIN_SESSIONS_FOR_ALERT = 10;

    // ─── Inline scoring (called from EventIngestionService) ──────────────────

    /**
     * Fast path: checks signals available at ingest time.
     * Runs inside the same request as event write — must be lightweight.
     */
    public void scoreInline(List<EventDto> events, String ipAddress,
                            String userAgent, Integer cfBotScore) {
        Map<String, Set<String>> typesBySession = new HashMap<>();
        Map<String, UUID> tenantBySession = new HashMap<>();

        for (EventDto e : events) {
            typesBySession.computeIfAbsent(e.getSessionId(), k -> new HashSet<>())
                    .add(e.getEventType());
            tenantBySession.put(e.getSessionId(), e.getTenantId());
        }

        for (Map.Entry<String, Set<String>> entry : typesBySession.entrySet()) {
            String sid = entry.getKey();
            Set<String> types = entry.getValue();
            UUID tenantId = tenantBySession.get(sid);

            List<String> reasons = new ArrayList<>();

            // Rule 1: PAGE_VIEW with no interaction (bot-like — no scroll/click/hover)
            if (types.contains("PAGE_VIEW")
                    && !types.contains("SCROLL")
                    && !types.contains("CLICK")
                    && !types.contains("HOVER")) {
                reasons.add("NO_INTERACTION");
            }

            // Rule 2: Known bot user-agent
            if (isBotUserAgent(userAgent)) {
                reasons.add("BOT_USER_AGENT");
            }

            // Rule 3: Cloudflare bot score (when Cloudflare is in front)
            if (cfBotScore != null && cfBotScore < CF_BOT_SCORE_THRESHOLD) {
                reasons.add("CF_BOT_SCORE:" + cfBotScore);
            }

            if (!reasons.isEmpty()) {
                markSuspicious(sid, tenantId, reasons);
            }
        }
    }

    // ─── Offline scoring (called hourly by FraudReviewScheduler) ─────────────

    /**
     * Retrospective review: checks session duration and IP clustering.
     * Only processes sessions not yet flagged.
     */
    public void reviewRecentSessions() {
        // Sessions completed in the last 2h, not yet flagged
        List<Map<String, Object>> sessions = jdbc.queryForList("""
                SELECT id, tenant_id, ip_address, user_agent,
                       started_at, last_event_at
                FROM sessions
                WHERE last_event_at > now() - interval '2 hours'
                  AND is_suspicious = false
                """);

        for (Map<String, Object> row : sessions) {
            String sid       = (String) row.get("id");
            UUID   tenantId  = (UUID)   row.get("tenant_id");
            List<String> reasons = new ArrayList<>();

            // Rule 4: Session too short (under 2 seconds total)
            Timestamp startedAt = (Timestamp) row.get("started_at");
            Timestamp lastAt    = (Timestamp) row.get("last_event_at");
            if (startedAt != null && lastAt != null
                    && (lastAt.getTime() - startedAt.getTime()) < 2_000) {
                reasons.add("SESSION_TOO_SHORT");
            }

            // Rule 5: IP+UA clustering (click-farm pattern)
            String ip = (String) row.get("ip_address");
            String ua = (String) row.get("user_agent");
            if (ip != null && !ip.isBlank() && ua != null && !ua.isBlank()) {
                int clusterCount = countRecentIpUaSessions(ip, ua);
                if (clusterCount > IP_UA_CLUSTER_THRESHOLD) {
                    reasons.add("IP_CLUSTER:" + clusterCount);
                }
            }

            if (!reasons.isEmpty()) {
                markSuspicious(sid, tenantId, reasons);
            }
        }

        alertOnHighFraudRate();
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private void markSuspicious(String sessionId, UUID tenantId, List<String> reasons) {
        float score = computeScore(reasons);
        String reasonsJson = reasons.stream()
                .map(r -> "\"" + r + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        try {
            jdbc.update("""
                    UPDATE sessions
                    SET is_suspicious  = true,
                        fraud_score    = ?,
                        fraud_reasons  = ?::jsonb
                    WHERE id = ? AND tenant_id = ?
                    """,
                    score, reasonsJson, sessionId, tenantId);

            log.debug("Fraud: session {} flagged — reasons={}, score={}", sessionId, reasons, score);
        } catch (Exception ex) {
            log.error("Fraud: failed to mark session {} suspicious", sessionId, ex);
        }
    }

    private int countRecentIpUaSessions(String ip, String ua) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM sessions
                WHERE ip_address = ? AND user_agent = ?
                  AND started_at > now() - interval '1 hour'
                """,
                Integer.class, ip, ua);
        return n != null ? n : 0;
    }

    private void alertOnHighFraudRate() {
        List<Map<String, Object>> stats = jdbc.queryForList("""
                SELECT tenant_id,
                       COUNT(*)                                          AS total,
                       SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END)   AS suspicious
                FROM sessions
                WHERE started_at > now() - interval '1 hour'
                GROUP BY tenant_id
                HAVING COUNT(*) >= ?
                """, MIN_SESSIONS_FOR_ALERT);

        for (Map<String, Object> row : stats) {
            long total      = ((Number) row.get("total")).longValue();
            long suspicious = ((Number) row.get("suspicious")).longValue();
            double rate     = (double) suspicious / total;

            if (rate > ALERT_FRAUD_RATE) {
                UUID tenantId = (UUID) row.get("tenant_id");
                log.warn("Fraud rate alert: tenant={} rate={}% ({}/{} sessions)",
                        tenantId, String.format("%.1f", rate * 100), suspicious, total);
                alertService.sendAlert(String.format(
                        "⚠️ Fraud Rate Alert — Tenant: %s\n\n" +
                        "Suspicious sessions (last 1h): %d / %d (%.1f%%)\n\n" +
                        "Action Required: Review traffic sources in Meta Ads Manager. " +
                        "Check for bot-like patterns or click farm activity.",
                        tenantId, suspicious, total, rate * 100));
            }
        }
    }

    private boolean isBotUserAgent(String ua) {
        if (ua == null || ua.isBlank()) return false;
        String lower = ua.toLowerCase();

        for (String allowed : BOT_UA_ALLOW) {
            if (lower.contains(allowed)) return false;
        }
        for (String bot : BOT_UA_DENY) {
            if (lower.contains(bot)) return true;
        }
        return false;
    }

    private float computeScore(List<String> reasons) {
        float score = 0f;
        for (String r : reasons) {
            if      (r.equals("BOT_USER_AGENT"))           score += 0.90f;
            else if (r.startsWith("CF_BOT_SCORE"))         score += 0.70f;
            else if (r.startsWith("IP_CLUSTER"))           score += 0.65f;
            else if (r.equals("SESSION_TOO_SHORT"))        score += 0.40f;
            else if (r.equals("NO_INTERACTION"))           score += 0.30f;
        }
        return Math.min(score, 1.0f);
    }
}
