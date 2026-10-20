package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reward Ladder — records reward events and triggers Thompson arm updates (vision §19.3).
 * FastAPI is the SOLE writer of thompson_arms alpha/beta. Spring only fires the notification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RewardService {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final AlertService  alertService;

    /**
     * Map a funnel stage to the Thompson reward type understood by FastAPI /decide/reward
     * (thompson_service.REWARD_MAP). Quality stages are CRM-confirmed outcomes.
     */
    static String rewardTypeForStage(String stageKey) {
        if (stageKey == null) return "CONVERSION";
        String k = stageKey.toUpperCase();
        if (k.endsWith("_HELD") || k.contains("MEETING") || k.contains("ATTENDED")) return "MEETING_ATTENDED";
        if (k.contains("ANSWERED")) return "CALL_ANSWERED";
        if (k.contains("FUNDS") || k.contains("PURCHASE") || k.contains("PAID") || k.contains("WON")) return "PURCHASE";
        if (k.contains("FORM_SUBMIT")) return "FORM_SUBMIT";
        return "CONVERSION";
    }

    /**
     * Called when a funnel stage is reached for a session.
     * Writes reward_events. If quality stage, also writes quality_gate_log and
     * posts the reward to FastAPI /decide/reward (binary increment 1.0 — FastAPI sole writer).
     * The decision_id is looked up in decision_log by (tenant, session, variant); the Redis
     * decision key alone expires after 1 h while CRM rewards arrive days later (audit 2.1/2.2).
     */
    public void onStageReached(UUID tenantId, String sessionId, UUID variantId, String stageKey) {
        List<Boolean> qualityCheck = jdbc.queryForList("""
            SELECT is_quality_key FROM funnel_stages
            WHERE tenant_id = ? AND stage_key = ?
            """, Boolean.class, tenantId, stageKey);
        boolean isQuality = !qualityCheck.isEmpty() && Boolean.TRUE.equals(qualityCheck.get(0));

        List<Double> weightList = jdbc.queryForList("""
            SELECT COALESCE(reward_weight, 1.0) FROM funnel_stages
            WHERE tenant_id = ? AND stage_key = ?
            """, Double.class, tenantId, stageKey);
        double rewardWeight = weightList.isEmpty() ? 1.0 : weightList.get(0);

        jdbc.update("""
            INSERT INTO reward_events
                (tenant_id, session_id, variant_id, stage_key, is_quality_stage, reward_value)
            VALUES (?, ?, ?, ?, ?, ?)
            """, tenantId, sessionId, variantId, stageKey, isQuality, rewardWeight);

        if (isQuality) {
            jdbc.update("""
                INSERT INTO quality_gate_log
                    (tenant_id, session_id, variant_id, stage_key)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (session_id, variant_id, stage_key) DO NOTHING
                """, tenantId, sessionId, variantId, stageKey);

            try {
                List<String> decisionIds = jdbc.queryForList("""
                    SELECT id::text FROM decision_log
                    WHERE tenant_id = ? AND session_id = ? AND chosen_variant_id = ?
                    ORDER BY decided_at DESC LIMIT 1
                    """, String.class, tenantId, sessionId, variantId);
                if (decisionIds.isEmpty()) {
                    log.info("No Thompson decision for session {} variant {} — reward not forwarded", sessionId, variantId);
                } else {
                    Map<String, Object> req = Map.of(
                        "decision_id", decisionIds.get(0),
                        "reward_type", rewardTypeForStage(stageKey)
                    );
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
                    restTemplate.postForObject(appProperties.getMlServiceUrl() + "/decide/reward",
                            new HttpEntity<>(req, headers), Map.class);
                }
            } catch (Exception e) {
                log.warn("Thompson reward notification failed for variant {}: {}", variantId, e.getMessage());
            }
        }

        log.debug("Stage reward: tenant={} stage={} quality={}", tenantId, stageKey, isQuality);
    }

    public Map<String, Object> getRewardLadder(UUID tenantId) {
        List<Map<String, Object>> stages = jdbc.queryForList("""
            SELECT
                fs.stage_key,
                fs.label AS stage_label,
                fs.ordinal,
                fs.is_quality_key,
                fs.reward_weight,
                fs.micro_reward_weight,
                cem.meta_event_name                                AS capi_event,
                COUNT(re.id)                                       AS total_rewards,
                COUNT(re.id) FILTER (WHERE re.is_quality_stage)    AS quality_rewards,
                COUNT(DISTINCT re.session_id)                      AS unique_sessions
            FROM funnel_stages fs
            LEFT JOIN reward_events re
                ON re.tenant_id = fs.tenant_id AND re.stage_key = fs.stage_key
            LEFT JOIN capi_event_map cem
                ON cem.tenant_id = fs.tenant_id AND cem.stage_key = fs.stage_key
            WHERE fs.tenant_id = ?
            GROUP BY fs.stage_key, fs.label, fs.ordinal,
                     fs.is_quality_key, fs.reward_weight, fs.micro_reward_weight,
                     cem.meta_event_name
            ORDER BY fs.ordinal
            """, tenantId);

        // Weekly counters: this week vs prev week
        List<Map<String, Object>> thisWeek = jdbc.queryForList("""
            SELECT stage_key, COUNT(*) AS cnt
            FROM reward_events
            WHERE tenant_id = ? AND rewarded_at >= date_trunc('week', NOW())
            GROUP BY stage_key
            """, tenantId);

        List<Map<String, Object>> prevWeek = jdbc.queryForList("""
            SELECT stage_key, COUNT(*) AS cnt
            FROM reward_events
            WHERE tenant_id = ?
              AND rewarded_at >= date_trunc('week', NOW()) - INTERVAL '7 days'
              AND rewarded_at <  date_trunc('week', NOW())
            GROUP BY stage_key
            """, tenantId);

        java.util.Map<String, Long> thisMap = thisWeek.stream().collect(
            java.util.stream.Collectors.toMap(
                r -> (String) r.get("stage_key"),
                r -> ((Number) r.get("cnt")).longValue()
            ));
        java.util.Map<String, Long> prevMap = prevWeek.stream().collect(
            java.util.stream.Collectors.toMap(
                r -> (String) r.get("stage_key"),
                r -> ((Number) r.get("cnt")).longValue()
            ));

        List<Map<String, Object>> weeklyCounters = stages.stream().map(s -> {
            String key  = (String) s.get("stage_key");
            long   curr = thisMap.getOrDefault(key, 0L);
            long   prev = prevMap.getOrDefault(key, 0L);
            Double delta = prev > 0 ? ((curr - prev) * 100.0 / prev) : null;
            return java.util.Map.<String, Object>of(
                "stage_key",       key,
                "count_this_week", curr,
                "count_prev_week", prev,
                "delta_pct",       delta != null ? Math.round(delta * 10.0) / 10.0 : null
            );
        }).collect(java.util.stream.Collectors.toList());

        return Map.of("stages", stages, "weekly_counters", weeklyCounters);
    }
}
