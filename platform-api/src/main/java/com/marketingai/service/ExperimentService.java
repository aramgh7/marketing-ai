package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * A/B Experiment Lifecycle (vision §3; arch §16.9).
 * Lifecycle: DRAFT → PENDING_REVIEW → APPROVED → ACTIVE → PAUSED/CONCLUDED → RETIRED
 * FastAPI is the SOLE writer of thompson_arms alpha/beta.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExperimentService {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final AlertService  alertService;

    public UUID createExperiment(UUID tenantId, UUID createdBy, Map<String, Object> body) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO experiments
                (id, tenant_id, name, hypothesis, experiment_type, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, tenantId,
            body.get("name"), body.get("hypothesis"),
            body.getOrDefault("experiment_type", "landing"), createdBy);
        return id;
    }

    public void submitForReview(UUID tenantId, UUID experimentId) {
        Integer variantCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM landing_variants
            WHERE experiment_id = ? AND status NOT IN ('ARCHIVED','RETIRED')
            """, Integer.class, experimentId);
        if (variantCount == null || variantCount < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Experiment needs ≥2 variants before review");
        }
        Integer controlCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM landing_variants
            WHERE experiment_id = ? AND is_control = TRUE
            """, Integer.class, experimentId);
        if (controlCount == null || controlCount != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Experiment needs exactly 1 control variant");
        }
        jdbc.update("""
            UPDATE experiments SET status = 'PENDING_REVIEW', updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND status = 'DRAFT'
            """, experimentId, tenantId);
        logEvent(tenantId, experimentId, "SUBMITTED", null, null);
    }

    public void approve(UUID tenantId, UUID experimentId, UUID approvedBy) {
        jdbc.update("""
            UPDATE experiments SET status = 'APPROVED', approved_by = ?, updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND status = 'PENDING_REVIEW'
            """, approvedBy, experimentId, tenantId);
        logEvent(tenantId, experimentId, "APPROVED", approvedBy, null);
    }

    public void start(UUID tenantId, UUID experimentId) {
        jdbc.update("""
            UPDATE experiments SET status = 'ACTIVE', started_at = NOW(), updated_at = NOW()
            WHERE id = ? AND tenant_id = ? AND status = 'APPROVED'
            """, experimentId, tenantId);
        jdbc.update("""
            UPDATE landing_variants SET status = 'ACTIVE'
            WHERE experiment_id = ? AND status = 'DRAFT'
            """, experimentId);
        logEvent(tenantId, experimentId, "STARTED", null, null);
        log.info("Experiment {} started", experimentId);
    }

    public void checkExperiment(UUID tenantId, UUID experimentId) {
        Map<String, Object> exp;
        try {
            exp = jdbc.queryForMap("""
                SELECT id, min_ess, auto_stop_p FROM experiments
                WHERE id = ? AND status = 'ACTIVE'
                """, experimentId);
        } catch (Exception e) { return; }

        List<Map<String, Object>> arms = jdbc.queryForList("""
            SELECT lv.id AS variant_id, lv.is_control,
                   COALESCE(ta.alpha, 1.0) AS alpha,
                   COALESCE(ta.beta,  1.0) AS beta
            FROM landing_variants lv
            LEFT JOIN thompson_arms ta
                ON ta.variant_id = lv.id AND ta.tenant_id = lv.tenant_id
                AND ta.reward_event = 'quality_gate_pass'
            WHERE lv.experiment_id = ? AND lv.status = 'ACTIVE'
            """, experimentId);

        if (arms.isEmpty()) return;

        int    minEss = ((Number) exp.get("min_ess")).intValue();
        double autoP  = ((Number) exp.get("auto_stop_p")).doubleValue();

        // Check auto-conclude via FastAPI
        try {
            Map<String, Object> req = Map.of("arms", arms, "min_ess", minEss, "auto_stop_p", autoP);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/decide/experiment/should-conclude", req, Map.class);
            if (result != null && Boolean.TRUE.equals(result.get("should_conclude"))) {
                String winnerId = (String) result.get("winner_variant_id");
                if (winnerId != null) {
                    concludeExperiment(tenantId, experimentId, UUID.fromString(winnerId),
                        (String) result.get("reason"));
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Conclude check failed for {}: {}", experimentId, e.getMessage());
        }

        // Check auto-pause arms via FastAPI
        try {
            Map<String, Object> req = Map.of("arms", arms, "min_ess", 50, "auto_stop_p", 0.95);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pauses = (List<Map<String, Object>>)
                restTemplate.postForObject(
                    appProperties.getMlServiceUrl() + "/decide/experiment/check-pause", req, List.class);
            if (pauses != null) {
                for (Map<String, Object> p : pauses) {
                    if (Boolean.TRUE.equals(p.get("should_pause"))) {
                        UUID variantId = UUID.fromString((String) p.get("variant_id"));
                        jdbc.update("""
                            UPDATE landing_variants SET status = 'PAUSED'
                            WHERE id = ? AND status = 'ACTIVE'
                            """, variantId);
                        alertService.sendAlert(
                            "Variant " + variantId + " auto-paused: " + p.get("reason"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Pause check failed for {}: {}", experimentId, e.getMessage());
        }
    }

    private void concludeExperiment(UUID tenantId, UUID experimentId, UUID winnerId, String reason) {
        jdbc.update("""
            UPDATE experiments
            SET status = 'CONCLUDED', concluded_at = NOW(),
                winner_variant_id = ?, updated_at = NOW()
            WHERE id = ? AND tenant_id = ?
            """, winnerId, experimentId, tenantId);
        logEvent(tenantId, experimentId, "CONCLUDED", null, reason);
        alertService.sendAlert(
            "Experiment " + experimentId + " concluded — winner: " + winnerId);
    }

    private void logEvent(UUID tenantId, UUID expId, String type, UUID by, String note) {
        jdbc.update("""
            INSERT INTO experiment_events (experiment_id, tenant_id, event_type, triggered_by, note)
            VALUES (?, ?, ?, ?, ?)
            """, expId, tenantId, type, by, note);
    }
}
