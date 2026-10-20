package com.marketingai.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.dto.webhook.GhlWebhookPayload;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GhlWebhookService {

    private final JdbcTemplate jdbc;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final TenantRepository tenantRepo;
    private final CAPIForwardingService capiService;
    private final AppointmentService appointmentService;
    private final FunnelService funnelService;
    private final ValueFormulaService valueFormulaService;
    private final RewardService rewardService;
    private final GhlApiClient ghlApiClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional
    public void process(UUID tenantId, GhlWebhookPayload payload) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        // 1. Log raw webhook for audit + replay
        UUID logId = logWebhook(tenantId, payload);

        String eventType = payload.getType();
        if (eventType == null) {
            log.warn("GHL webhook missing 'type' field for tenant {}", tenantId);
            return;
        }

        log.info("GHL webhook received: type={} contact={} tenant={}",
                eventType, payload.getContactId(), tenantId);

        // 2. Link GHL contact to session (email match — arch v1.1 §13.2)
        // ContactCreate fires for new contacts; ContactUpdate for returning leads.
        // Without ContactUpdate handling, returning leads are unattributed.
        // Roadmap 8-9 linking order: our ids in custom fields → email → phone → UTM (last resort)
        String sessionId = null;
        GhlContactLinker.Method method = GhlContactLinker.Method.NONE;
        Optional<String> fromField = GhlContactLinker.extractCustomField(payload.getRawData(), GhlApiClient.FIELD_SESSION);
        if (fromField.isPresent() && sessionExists(tenantId, fromField.get())) {
            sessionId = fromField.get();
            method = GhlContactLinker.Method.CUSTOM_FIELD;
        }
        if (sessionId == null && payload.getEmail() != null) {
            sessionId = findSessionByEmail(tenantId, payload.getEmail());
            if (sessionId != null) method = GhlContactLinker.Method.EMAIL;
        }
        if (sessionId == null && payload.getPhone() != null) {
            sessionId = findSessionByPhone(tenantId, payload.getPhone());
            if (sessionId != null) method = GhlContactLinker.Method.PHONE;
        }
        if (sessionId == null) {
            sessionId = findSessionByUtm(tenantId, payload.getRawData());
            if (sessionId != null) method = GhlContactLinker.Method.UTM;
        }
        if (sessionId != null) {
            updateSessionGhlFields(sessionId, tenantId, payload);
            // Write mai_session_id / mai_event_id into the contact BEFORE any sub-account transfer (P1)
            if (method != GhlContactLinker.Method.CUSTOM_FIELD) {
                writeContactIds(tenantId, payload, sessionId);
            }
        }
        markLinkMethod(logId, method);

        // 3. Handle appointment lifecycle with real GHL event names (arch v1.1 §13.1)
        handleAppointmentEvent(tenantId, eventType, sessionId, payload);

        // 4. Forward to CAPI — use DB-driven funnel if available, else legacy gate.
        String stageKey = funnelService.resolveStage(
                tenantId, eventType, payload.getStatus(), payload.getAppointmentStatus());
        // Audit 4.4: a deterministic event_id lets Meta dedup CAPI against the browser pixel and
        // makes webhook retries idempotent on Meta's side.
        String anchor = firstNonBlank(payload.getAppointmentId(), payload.getOpportunityId(),
                payload.getContactId(), payload.getEmail());
        if (!stageKey.isBlank()) {
            // CAPI v2: resolve session qualifiers for value formula
            Boolean selfAccredited = lookupSelfAccredited(sessionId, tenantId);
            String  ticketBucket   = lookupTicketBucket(sessionId, tenantId);
            capiService.forwardByStage(
                    tenant, stageKey,
                    payload.getContactId(), sessionId,
                    payload.getEmail(), payload.getPhone(),
                    selfAccredited, ticketBucket,
                    payload.getDealValue(),
                    CAPIForwardingService.deterministicEventId(tenantId, anchor, stageKey),
                    valueFormulaService);
            // Reward ladder: notify RewardService of reached stage (8-3)
            if (sessionId != null) {
                try {
                    // variant_id from session (best-effort — may be null for old sessions)
                    String variantIdStr = lookupVariantId(sessionId, tenantId);
                    if (variantIdStr != null) {
                        rewardService.onStageReached(tenantId, sessionId, UUID.fromString(variantIdStr), stageKey);
                    }
                } catch (Exception e) {
                    log.warn("Reward stage notification failed: {}", e.getMessage());
                }
            }
        } else {
            // Legacy B2B quality gate fallback
            String capiKey = CAPIForwardingService.resolveGhlKey(
                    eventType, payload.getStatus(), payload.getAppointmentStatus());
            capiService.forward(
                    tenant, capiKey,
                    payload.getContactId(), sessionId,
                    payload.getEmail(), payload.getPhone(),
                    payload.getDealValue(),
                    CAPIForwardingService.deterministicEventId(tenantId, anchor, capiKey));
        }
    }

    // ─── Appointment event handling (real GHL event names — arch v1.1 §13.1) ──

    private void handleAppointmentEvent(UUID tenantId, String eventType,
                                        String sessionId, GhlWebhookPayload payload) {
        switch (eventType) {
            // New appointment booked via GHL calendar
            case "AppointmentCreate" -> {
                Instant scheduledAt = parseInstant(payload.getStartTime());
                appointmentService.registerAppointment(
                        tenantId, sessionId, payload.getContactId(),
                        payload.getAppointmentId(),
                        scheduledAt != null ? scheduledAt : Instant.now()
                );
            }
            // AppointmentUpdate carries appointmentStatus: "showed" | "noshow"
            // Status is set MANUALLY by the sales rep in GHL (arch v1.1 §13.1).
            // Google Calendar API is NOT used — it only shows invite accepted/declined, not attendance.
            case "AppointmentUpdate" -> {
                String apptStatus = payload.getAppointmentStatus();
                if ("showed".equalsIgnoreCase(apptStatus)) {
                    appointmentService.markCompleted(tenantId, payload.getAppointmentId(), payload.getContactId());
                } else if ("noshow".equalsIgnoreCase(apptStatus)) {
                    appointmentService.markNoShow(tenantId, payload.getAppointmentId(), payload.getContactId());
                } else {
                    log.debug("AppointmentUpdate with unhandled status='{}' for appointment {}",
                            apptStatus, payload.getAppointmentId());
                }
            }
            default -> { /* ContactCreate, ContactUpdate, OpportunityStatusUpdate handled via CAPI */ }
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    private Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            log.warn("Could not parse appointment startTime: {}", iso);
            return null;
        }
    }

    // ─── Session matching ─────────────────────────────────────────────────────

    /**
     * Find the most recent session for a tenant where a FORM_SUBMIT event
     * was recorded with the given email in event_data.
     */
    private String findSessionByEmail(UUID tenantId, String email) {
        try {
            return jdbc.queryForObject(
                    """
                    SELECT s.id FROM sessions s
                    JOIN events e ON e.session_id = s.id AND e.event_time > now() - INTERVAL '30 days'
                    WHERE s.tenant_id = ?
                      AND e.event_type = 'FORM_SUBMIT'
                      AND lower(e.event_data->>'email') = lower(?)
                    ORDER BY e.event_time DESC
                    LIMIT 1
                    """,
                    String.class, tenantId, email
            );
        } catch (Exception e) {
            return null; // no match found
        }
    }

    private boolean sessionExists(UUID tenantId, String sessionId) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id = ? AND tenant_id = ?",
                    Integer.class, sessionId, tenantId);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Phone digits match against the form submission (sessions themselves store no phone). */
    private String findSessionByPhone(UUID tenantId, String phone) {
        String digits = GhlContactLinker.normalisePhone(phone);
        if (digits == null) return null;
        try {
            List<String> rows = jdbc.queryForList("""
                    SELECT session_id FROM form_submissions
                    WHERE tenant_id = ? AND session_id IS NOT NULL
                      AND regexp_replace(COALESCE(phone, ''), '[^0-9]', '', 'g') = ?
                      AND submitted_at > now() - INTERVAL '30 days'
                    ORDER BY submitted_at DESC LIMIT 1
                    """, String.class, tenantId, digits);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** UTM last resort: a converted session of the same utm_campaign/source within 24 h of the webhook. */
    private String findSessionByUtm(UUID tenantId, Map<String, Object> raw) {
        if (raw == null) return null;
        Object campaign = raw.get("utm_campaign"); Object source = raw.get("utm_source");
        if (campaign == null && source == null) {
            Object attribution = raw.get("attributionSource");
            if (attribution instanceof Map<?, ?> a) { campaign = a.get("utmCampaign"); source = a.get("utmSource"); }
        }
        if (campaign == null && source == null) return null;
        try {
            List<String> rows = jdbc.queryForList("""
                    SELECT id FROM sessions
                    WHERE tenant_id = ? AND converted = TRUE AND ghl_contact_id IS NULL
                      AND last_event_at > now() - INTERVAL '24 hours'
                      AND (? IS NULL OR utm_campaign = ?) AND (? IS NULL OR utm_source = ?)
                    ORDER BY last_event_at DESC LIMIT 1
                    """, String.class, tenantId,
                    campaign == null ? null : campaign.toString(), campaign == null ? null : campaign.toString(),
                    source == null ? null : source.toString(), source == null ? null : source.toString());
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** Roadmap 8-9: our ids into the contact's custom fields through the location's API key. */
    private void writeContactIds(UUID tenantId, GhlWebhookPayload p, String sessionId) {
        if (p.getContactId() == null) return;
        try {
            List<byte[]> keys = jdbc.queryForList("""
                    SELECT api_key_enc FROM ghl_subaccounts
                    WHERE tenant_id = ? AND is_active = TRUE AND api_key_enc IS NOT NULL
                      AND (location_id = ? OR role = 'MAIN')
                    ORDER BY (location_id = ?) DESC LIMIT 1
                    """, byte[].class, tenantId, p.getLocationId(), p.getLocationId());
            if (keys.isEmpty()) return;
            List<String> eventIds = jdbc.queryForList("""
                    SELECT event_id FROM events WHERE session_id = ? AND tenant_id = ? AND event_type = 'FORM_SUBMIT'
                    ORDER BY event_time DESC LIMIT 1
                    """, String.class, sessionId, tenantId);
            ghlApiClient.writeContactIds(vault.open(keys.get(0)),
                    p.getContactId(), sessionId, eventIds.isEmpty() ? null : eventIds.get(0));
        } catch (Exception e) {
            log.debug("writeContactIds skipped: {}", e.getMessage());
        }
    }

    private void markLinkMethod(UUID logId, GhlContactLinker.Method method) {
        if (logId == null) return;
        try {
            jdbc.update("UPDATE ghl_webhook_log SET link_method = ?, processed = TRUE WHERE id = ?",
                    method.name().toLowerCase(), logId);
        } catch (Exception ignored) { /* audit only */ }
    }

    private void updateSessionGhlFields(String sessionId, UUID tenantId, GhlWebhookPayload p) {
        // arch v1.1 §13.1: real GHL event names — OpportunityStatusUpdate with status field
        String eventType = p.getType();
        String statusField = p.getStatus(); // "won" | "lost" from OpportunityStatusUpdate
        jdbc.update(
                """
                UPDATE sessions SET
                    ghl_contact_id     = COALESCE(ghl_contact_id, ?),
                    ghl_opportunity_id = COALESCE(ghl_opportunity_id, ?),
                    pipeline_stage     = COALESCE(?, pipeline_stage),
                    deal_value         = COALESCE(?, deal_value),
                    deal_status        = CASE
                        WHEN ? = 'OpportunityStatusUpdate' AND ? = 'won'  THEN 'won'
                        WHEN ? = 'OpportunityStatusUpdate' AND ? = 'lost' THEN 'lost'
                        ELSE deal_status
                    END,
                    converted = CASE
                        WHEN ? = 'OpportunityStatusUpdate' AND ? = 'won' THEN true
                        ELSE converted
                    END
                WHERE id = ? AND tenant_id = ?
                """,
                p.getContactId(),
                p.getOpportunityId(),
                p.getPipelineStage(),
                p.getDealValue() != null ? p.getDealValue().floatValue() : null,
                eventType, statusField,
                eventType, statusField,
                eventType, statusField,
                sessionId, tenantId
        );
        log.debug("Session {} linked to GHL contact {}", sessionId, p.getContactId());
    }

    // ─── Session qualifier lookups for CAPI v2 ───────────────────────────────

    private Boolean lookupSelfAccredited(String sessionId, UUID tenantId) {
        if (sessionId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT self_accredited FROM sessions WHERE id = ? AND tenant_id = ? LIMIT 1",
                    Boolean.class, sessionId, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private String lookupTicketBucket(String sessionId, UUID tenantId) {
        if (sessionId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT ticket_bucket FROM sessions WHERE id = ? AND tenant_id = ? LIMIT 1",
                    String.class, sessionId, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private String lookupVariantId(String sessionId, UUID tenantId) {
        if (sessionId == null) return null;
        try {
            return jdbc.queryForObject(
                    "SELECT variant_id::text FROM sessions WHERE id = ? AND tenant_id = ? LIMIT 1",
                    String.class, sessionId, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Webhook audit log ────────────────────────────────────────────────────

    private UUID logWebhook(UUID tenantId, GhlWebhookPayload payload) {
        try {
            String json = MAPPER.writeValueAsString(payload);
            return jdbc.queryForObject(
                    """
                    INSERT INTO ghl_webhook_log
                        (tenant_id, event_type, ghl_contact_id, ghl_opportunity_id, payload, location_id)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?)
                    RETURNING id
                    """,
                    UUID.class,
                    tenantId,
                    payload.getType(),
                    payload.getContactId(),
                    payload.getOpportunityId(),
                    json,
                    payload.getLocationId()
            );
        } catch (Exception e) {
            log.warn("Failed to log GHL webhook: {}", e.getMessage());
            return null;
        }
    }
}
