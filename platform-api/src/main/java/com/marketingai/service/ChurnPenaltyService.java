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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Delayed Churn Penalty (vision §6.7): retroactively penalise Thompson arms
 * whose sessions subsequently lost the deal in GHL.
 *
 * Attribution: sessions.deal_status='lost'
 *   → decision_log.session_id → chosen_variant_id + context_key
 *   → thompson_arms → arm_id
 *
 * Rules (§6.7):
 *   - min cohort size = 20 decisions for the arm before recalc
 *   - decay weight per lost session: exp(-0.02 * days_since_decision)
 *   - penalty cap: 30% of (α+β) per recalc (enforced in FastAPI)
 *   - force-close sessions after 90 days even if cohort never reached 20
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChurnPenaltyService {

    private static final int MIN_COHORT_SIZE    = 20;
    private static final int FORCE_CLOSE_DAYS   = 90;
    private static final double DECAY_LAMBDA    = 0.02; // λ per §6.7

    private final JdbcTemplate    jdbc;
    private final RestTemplate    restTemplate;
    private final AppProperties   appProperties;

    /**
     * Run one full churn penalty scan for all tenants.
     * Called daily by ChurnPenaltyScheduler.
     */
    public void runScan() {
        List<UUID> tenantIds = jdbc.queryForList(
                "SELECT id FROM tenants", UUID.class);
        for (UUID tenantId : tenantIds) {
            try {
                scanTenant(tenantId);
            } catch (Exception e) {
                log.error("Churn penalty scan failed for tenant {}: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    private void scanTenant(UUID tenantId) {
        // Find unprocessed lost sessions with their arm attribution
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT
                    s.id               AS session_id,
                    s.started_at,
                    dl.decided_at,
                    dl.chosen_variant_id::text AS variant_id,
                    COALESCE(dl.context->>'context_key', 'global') AS context_key,
                    ta.id::text        AS arm_id
                FROM   sessions s
                JOIN   decision_log dl
                    ON  dl.session_id = s.id
                    AND dl.tenant_id  = s.tenant_id
                JOIN   thompson_arms ta
                    ON  ta.variant_id  = dl.chosen_variant_id
                    AND ta.tenant_id   = s.tenant_id
                    AND ta.context_key = COALESCE(dl.context->>'context_key', 'global')
                WHERE  s.deal_status        = 'lost'
                  AND  s.churn_penalized_at IS NULL
                  AND  s.tenant_id          = ?
                ORDER BY dl.decided_at ASC
                """,
                tenantId
        );

        if (rows.isEmpty()) return;

        Instant now = Instant.now();

        // Group rows by arm_id + context_key
        record ArmKey(String armId, String contextKey) {}
        Map<ArmKey, List<Map<String, Object>>> byArm = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            ArmKey key = new ArmKey(
                    (String) row.get("arm_id"),
                    (String) row.get("context_key")
            );
            byArm.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        Set<String> sessionIdsToClose = new HashSet<>();

        for (Map.Entry<ArmKey, List<Map<String, Object>>> entry : byArm.entrySet()) {
            ArmKey key = entry.getKey();
            List<Map<String, Object>> armRows = entry.getValue();

            // Cohort size = total decisions ever made for this arm
            Integer cohortSize = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)::int
                    FROM decision_log dl
                    JOIN thompson_arms ta
                        ON  ta.variant_id  = dl.chosen_variant_id
                        AND ta.tenant_id   = dl.tenant_id
                        AND ta.context_key = COALESCE(dl.context->>'context_key', 'global')
                        AND ta.id::text    = ?
                    WHERE dl.tenant_id = ?
                    """,
                    Integer.class,
                    key.armId(),
                    tenantId
            );
            if (cohortSize == null) cohortSize = 0;

            // Force-close sessions older than FORCE_CLOSE_DAYS to avoid endless retries
            boolean forceClose = armRows.stream().anyMatch(r -> {
                Instant decidedAt = toInstant(r.get("decided_at"));
                return decidedAt != null
                        && ChronoUnit.DAYS.between(decidedAt, now) > FORCE_CLOSE_DAYS;
            });

            if (cohortSize >= MIN_COHORT_SIZE) {
                double weightedSum = armRows.stream().mapToDouble(r -> {
                    Instant decidedAt = toInstant(r.get("decided_at"));
                    if (decidedAt == null) return 0.0;
                    double days = ChronoUnit.SECONDS.between(decidedAt, now) / 86400.0;
                    return Math.exp(-DECAY_LAMBDA * days);
                }).sum();

                callFastApiChurnPenalty(
                        tenantId.toString(),
                        key.armId(),
                        key.contextKey(),
                        cohortSize,
                        armRows.size(),
                        weightedSum
                );
                armRows.forEach(r -> sessionIdsToClose.add((String) r.get("session_id")));

            } else if (forceClose) {
                // Cohort too small even after 90 days — mark processed, skip penalty
                log.debug("Force-closing {} lost sessions for arm {} (cohort={} < {})",
                        armRows.size(), key.armId(), cohortSize, MIN_COHORT_SIZE);
                armRows.forEach(r -> sessionIdsToClose.add((String) r.get("session_id")));
            }
            // else: cohort still growing — leave unprocessed, retry tomorrow
        }

        if (!sessionIdsToClose.isEmpty()) {
            markSessionsProcessed(tenantId, sessionIdsToClose, now);
        }
    }

    private void callFastApiChurnPenalty(
            String tenantId, String armId, String contextKey,
            int cohortSize, int churnCount, double weightedSum) {
        String url = appProperties.getMlServiceUrl() + "/decide/churn-penalty";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        Map<String, Object> body = Map.of(
                "tenant_id",   tenantId,
                "arm_id",      armId,
                "context_key", contextKey,
                "cohort_size", cohortSize,
                "churn_count", churnCount,
                "weighted_sum", weightedSum
        );
        try {
            restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
            log.info("Churn penalty applied: arm={} cohort={} churned={} weighted_sum={}",
                    armId, cohortSize, churnCount, weightedSum);
        } catch (Exception e) {
            log.error("Churn penalty FastAPI call failed for arm {}: {}", armId, e.getMessage());
        }
    }

    private void markSessionsProcessed(UUID tenantId, Set<String> sessionIds, Instant now) {
        for (String sessionId : sessionIds) {
            jdbc.update(
                    "UPDATE sessions SET churn_penalized_at = ? WHERE id = ? AND tenant_id = ?",
                    java.sql.Timestamp.from(now), sessionId, tenantId
            );
        }
    }

    private static Instant toInstant(Object val) {
        if (val instanceof Instant i)   return i;
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }
}
