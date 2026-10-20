package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import com.marketingai.service.BookingWindowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Propensity Model endpoints (Phase 5-2, §7.3 Этап 2).
 *
 * Spring Boot persists P(convert) + main_objection to session_propensity.
 * Gradient Boosting scoring is delegated to FastAPI /propensity/score.
 *
 * Caller must ensure consent_level='full' before calling /score (§12.1a).
 * Spring Boot weekly fit scheduler is in PropensityFitScheduler.
 */
@Slf4j
@RestController
@RequestMapping("/propensity")
@RequiredArgsConstructor
public class PropensityController {

    private final RestTemplate        restTemplate;
    private final AppProperties       appProperties;
    private final JdbcTemplate        jdbc;
    private final BookingWindowService bookingWindowService;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/propensity/score — score a single session
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/score")
    public ResponseEntity<Map<String, Object>> scoreSession(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String sessionId = (String) body.get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session_id required");
        }

        // Enforce consent gate (§12.1a)
        try {
            String consent = jdbc.queryForObject("""
                    SELECT COALESCE(consent_level, 'none')
                    FROM sessions WHERE id = ? AND tenant_id = ?
                    """, String.class, sessionId, tenantId);
            if (!"full".equals(consent)) {
                return ResponseEntity.ok(Map.of(
                        "p_convert",      0.0,
                        "main_objection", (Object) null,
                        "uplift_score",   0.0,
                        "skipped",        true,
                        "reason",         "no_consent"
                ));
            }
        } catch (Exception e) {
            log.debug("Session {} not found, proceeding without consent check", sessionId);
        }

        // Load cluster assignment for contextual propensity
        int clusterId = -1;
        try {
            Integer cid = jdbc.queryForObject("""
                    SELECT cluster_id FROM sessions WHERE id = ? AND tenant_id = ?
                    """, Integer.class, sessionId, tenantId);
            if (cid != null) clusterId = cid;
        } catch (Exception ignored) {}

        // Load stored model bytes + HMAC for tenant (propensity_models); unsigned bytes are refused by ML
        Map<String, Object> model = loadModel(tenantId);
        String modelB64 = model == null ? null : (String) model.get("model_b64");
        String modelSig = model == null ? null : (String) model.get("model_sig");

        Map<String, Object> mlBody = new HashMap<>();
        mlBody.put("tenant_id",  tenantId.toString());
        mlBody.put("session",    body.getOrDefault("session", Map.of()));
        mlBody.put("events",     body.getOrDefault("events", List.of()));
        mlBody.put("cluster_id", clusterId);
        if (modelB64 != null && modelSig != null) {
            mlBody.put("model_b64", modelB64);
            mlBody.put("model_sig", modelSig);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

        String url = appProperties.getMlServiceUrl() + "/propensity/score";
        @SuppressWarnings("unchecked")
        Map<String, Object> result = restTemplate.postForObject(
                url, new HttpEntity<>(mlBody, headers), Map.class);

        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service returned null");
        }

        double pConvert    = ((Number) result.getOrDefault("p_convert",    0.0)).doubleValue();
        String objection   = (String)  result.get("main_objection");
        double upliftScore = ((Number) result.getOrDefault("uplift_score", 0.0)).doubleValue();

        // Persist to session_propensity
        jdbc.update("""
                INSERT INTO session_propensity
                    (session_id, tenant_id, p_convert, main_objection, uplift_score, cluster_id, scored_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (session_id, tenant_id) DO UPDATE SET
                    p_convert      = EXCLUDED.p_convert,
                    main_objection = EXCLUDED.main_objection,
                    uplift_score   = EXCLUDED.uplift_score,
                    cluster_id     = EXCLUDED.cluster_id,
                    scored_at      = now()
                """, sessionId, tenantId, pConvert, objection, upliftScore,
                clusterId >= 0 ? clusterId : null);

        log.debug("Propensity scored: tenant={} session={} p={} objection={}",
                tenantId, sessionId, pConvert, objection);

        return ResponseEntity.ok(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/propensity/session/{sessionId} — retrieve stored score
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> getScore(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable String sessionId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p_convert, main_objection, uplift_score, cluster_id, scored_at
                FROM session_propensity
                WHERE session_id = ? AND tenant_id = ?
                """, sessionId, tenantId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No propensity score for session " + sessionId);
        }

        return ResponseEntity.ok(rows.get(0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** GET /api/v1/propensity/booking-window — dynamic slot window by risk tier (roadmap 8-15). */
    @GetMapping("/booking-window")
    public ResponseEntity<Map<String, Object>> bookingWindow(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam("session_id") UUID sessionId) {
        return ResponseEntity.ok(bookingWindowService.getBookingWindow(tenantId, sessionId));
    }

    /** Load base64-encoded model bytes from DB if a trained model exists. */
    private Map<String, Object> loadModel(UUID tenantId) {
        try {
            return jdbc.queryForMap("""
                    SELECT model_b64, model_sig FROM propensity_models
                    WHERE tenant_id = ?
                    ORDER BY fitted_at DESC LIMIT 1
                    """, tenantId);
        } catch (Exception e) {
            return null;  // No model yet — FastAPI will use rule-based scoring
        }
    }
}
