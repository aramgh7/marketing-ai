package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.config.AppProperties;
import com.marketingai.security.TenantContext;
import com.marketingai.service.CallRecordingConsent;
import com.marketingai.service.MlServiceClient;
import com.marketingai.util.Jsonb;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Call-recording webhook (roadmap 8-18; vision §19.18.5): GHL / Zoom post the recording URL after
 * a call. The recording is transcribed by Whisper only when recording consent is satisfied for the
 * lead's US state, stored redacted (PII placeholders), and its keyword objection tags pre-fill the
 * coordinator's post-call note.
 *
 *   POST /webhooks/call-recordings/{tenantId}   (X-Webhook-Secret like the GHL webhook)
 *   GET  /post-call/recordings?appointment_id=  (authenticated, see PostCallController)
 */
@RestController
@RequestMapping("/webhooks/call-recordings")
@RequiredArgsConstructor
@Slf4j
public class CallRecordingController {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;
    private final AppProperties appProperties;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/{tenantId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable UUID tenantId,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String headerSecret,
            @RequestParam(value = "secret", required = false) String querySecret,
            @RequestBody Map<String, Object> body) {

        String provided = headerSecret != null ? headerSecret : querySecret;
        String expected = TenantContext.callAsSystem(() -> expectedSecret(tenantId, str(body.get("location_id"))));
        if (!GhlWebhookController.secretMatches(provided, expected)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        String url = str(body.get("recording_url"));
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "recording_url (http/https) required"));
        }

        Map<String, Object> result = new HashMap<>();
        TenantContext.runAs(tenantId.toString(), () -> result.putAll(process(tenantId, body, url)));
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> process(UUID tenantId, Map<String, Object> body, String url) {
        // Resolve the appointment (by our id or the GHL appointment id) and its session / lead state
        UUID appointmentId = null;
        String sessionId = null;
        String stateCode = str(body.get("state_code"));
        try {
            if (body.get("appointment_id") != null) appointmentId = UUID.fromString(str(body.get("appointment_id")));
        } catch (Exception ignored) { /* not ours */ }
        List<Map<String, Object>> appt = appointmentId != null
                ? jdbc.queryForList("SELECT id, session_id FROM appointments WHERE id = ? AND tenant_id = ?", appointmentId, tenantId)
                : body.get("ghl_appointment_id") != null
                    ? jdbc.queryForList("SELECT id, session_id FROM appointments WHERE ghl_appointment_id = ? AND tenant_id = ? ORDER BY scheduled_at DESC LIMIT 1",
                        str(body.get("ghl_appointment_id")), tenantId)
                    : List.of();
        if (!appt.isEmpty()) {
            appointmentId = (UUID) appt.get(0).get("id");
            sessionId = (String) appt.get(0).get("session_id");
        }
        if (stateCode == null && sessionId != null) {
            List<String> geo = jdbc.queryForList("SELECT geo FROM sessions WHERE id = ? AND tenant_id = ?", String.class, sessionId, tenantId);
            // geo may be "US-CA" or a bare country; only a trailing state code is used
            if (!geo.isEmpty() && geo.get(0) != null && geo.get(0).matches("(?i)US[-_ ]?[A-Z]{2}")) {
                stateCode = geo.get(0).substring(geo.get(0).length() - 2).toUpperCase();
            }
        }

        boolean requireWhenUnknown = !"false".equalsIgnoreCase(String.valueOf(tenantSetting(tenantId, "call_recording_consent_required_when_unknown", "true")));
        Boolean consentFlag = body.get("consent_granted") instanceof Boolean b ? b
                : body.get("consent_granted") != null ? Boolean.valueOf(str(body.get("consent_granted"))) : null;
        CallRecordingConsent.Decision decision = CallRecordingConsent.decide(stateCode, consentFlag, requireWhenUnknown);

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO call_recordings
                    (id, tenant_id, appointment_id, session_id, ghl_contact_id, ghl_appointment_id, provider,
                     recording_url, duration_sec, state_code, consent_state, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, appointmentId, sessionId, str(body.get("contact_id")), str(body.get("ghl_appointment_id")),
                str(body.getOrDefault("provider", "GHL")).toUpperCase(), url,
                body.get("duration_sec") instanceof Number n ? n.intValue() : null,
                stateCode, decision.name(), CallRecordingConsent.mayTranscribe(decision) ? "RECEIVED" : "SKIPPED_CONSENT");

        if (!CallRecordingConsent.mayTranscribe(decision)) {
            log.info("Call recording {} not transcribed: consent {} (state {})", id, decision, stateCode);
            return Map.of("id", id.toString(), "status", "SKIPPED_CONSENT", "consent", decision.name());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ml = mlClient.post("/post-call/transcribe-recording", Map.of(
                    "tenant_id", tenantId.toString(), "recording_id", id.toString(),
                    "recording_url", url, "language", body.getOrDefault("language", "en")), Map.class);
            if (ml == null) throw new IllegalStateException("ml-service returned null");
            jdbc.update("""
                    UPDATE call_recordings
                    SET status = 'TRANSCRIBED', transcript_redacted = ?, pii_counts = ?::jsonb,
                        objection_tags = ?::jsonb, summary = ?, transcribe_provider = ?, transcribed_at = ?
                    WHERE id = ?
                    """, str(ml.get("transcript_redacted")), toJson(ml.getOrDefault("pii_counts", Map.of())),
                    toJson(ml.getOrDefault("objection_tags", List.of())), str(ml.get("summary")),
                    str(ml.get("provider")), Timestamp.from(Instant.now()), id);
            return Map.of("id", id.toString(), "status", "TRANSCRIBED", "consent", decision.name(),
                    "objection_tags", ml.getOrDefault("objection_tags", List.of()));
        } catch (Exception e) {
            jdbc.update("UPDATE call_recordings SET status = 'FAILED', error = ? WHERE id = ?", e.getMessage(), id);
            log.warn("Call recording {} transcription failed: {}", id, e.getMessage());
            return Map.of("id", id.toString(), "status", "FAILED", "error", String.valueOf(e.getMessage()));
        }
    }

    private String expectedSecret(UUID tenantId, String locationId) {
        try {
            if (locationId != null) {
                List<String> sub = jdbc.queryForList(
                        "SELECT webhook_secret FROM ghl_subaccounts WHERE tenant_id = ? AND location_id = ? AND is_active = TRUE",
                        String.class, tenantId, locationId);
                if (!sub.isEmpty() && sub.get(0) != null && !sub.get(0).isBlank()) return sub.get(0);
            }
            List<String> rows = jdbc.queryForList(
                    "SELECT settings->>'ghl_webhook_secret' FROM tenants WHERE id = ?", String.class, tenantId);
            if (!rows.isEmpty() && rows.get(0) != null && !rows.get(0).isBlank()) return rows.get(0);
        } catch (Exception e) {
            log.debug("secret lookup failed: {}", e.getMessage());
        }
        return appProperties.getGhlWebhookSecret();
    }

    private Object tenantSetting(UUID tenantId, String key, Object dflt) {
        try {
            List<Object> rows = jdbc.queryForList("SELECT settings FROM tenants WHERE id = ?", Object.class, tenantId);
            Object v = rows.isEmpty() ? null : Jsonb.asMap(rows.get(0)).get(key);
            return v != null ? v : dflt;
        } catch (Exception e) {
            return dflt;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static String toJson(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }
}
