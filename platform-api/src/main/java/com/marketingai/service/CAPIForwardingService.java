package com.marketingai.service;

import java.time.Duration;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.entity.CAPIEventLog;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.CAPIEventLogRepository;
import com.marketingai.repository.MetaPixelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CAPIForwardingService {

    private final CAPIEventLogRepository capiRepo;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final CapiValueModeService capiValueModeService;   // roadmap 9-9: PROXY | FIXED | AB
    private final MetaPixelRepository pixelRepo;
    private final RestTemplate restTemplate;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GRAPH_API = "https://graph.facebook.com/v20.0";

    // ─── B2B proxy value bidding (vision §16.1, arch v1.1 §13.4) ────────────

    public record ProxyMapping(String metaEventName, float multiplier) {}

    /**
     * Real GHL event types → CAPI event mapping (arch v1.1 §13.4).
     * Each pipeline stage sends a DIFFERENT CAPI event so one lead never counts as two Purchases.
     *   call outcome (conversation webhook)         → Lead       (×0.15 avg deal value)
     *   AppointmentUpdate, appointmentStatus=showed → Schedule   (×0.40)
     *   OpportunityStatusUpdate, status=won         → Purchase   (×1.00, actual deal value)
     *
     * Not mapped (intentionally skipped):
     *   ContactCreate / ContactUpdate — session linking only, no CAPI send
     *   AppointmentUpdate, noshow — no CAPI event (do not pollute pixel)
     *   AppointmentCreate — booking only, quality not confirmed yet
     *   OpportunityStatusUpdate, status=lost — no CAPI event
     */
    private static final Map<String, ProxyMapping> GHL_TO_CAPI = Map.of(
            // ⚠️ verify exact event name + status field in GHL docs before go-live
            "CRM_CALL_ANSWERED",        new ProxyMapping("Lead",     0.15f),
            "AppointmentUpdate_showed", new ProxyMapping("Schedule", 0.40f),
            "OpportunityStatusUpdate_won", new ProxyMapping("Purchase", 1.00f)
    );

    /**
     * Resolves the lookup key from raw GHL event type + status fields.
     * AppointmentUpdate and OpportunityStatusUpdate carry their outcome in a separate field.
     */
    public static String resolveGhlKey(String eventType, String statusField, String appointmentStatus) {
        if ("AppointmentUpdate".equals(eventType) && "showed".equalsIgnoreCase(appointmentStatus)) {
            return "AppointmentUpdate_showed";
        }
        if ("OpportunityStatusUpdate".equals(eventType) && "won".equalsIgnoreCase(statusField)) {
            return "OpportunityStatusUpdate_won";
        }
        // call outcome: conversation/message webhooks — exact name TBD in GHL docs
        if ("CRM_CALL_ANSWERED".equals(eventType)) {
            return "CRM_CALL_ANSWERED";
        }
        return eventType; // no match → qualityGate returns empty → SKIPPED
    }

    /** Returns empty if the GHL event should be skipped (quality gate failed). */
    public Optional<ProxyMapping> qualityGate(String ghlEventType) {
        return Optional.ofNullable(GHL_TO_CAPI.get(ghlEventType));
    }

    // ─── Main entry point ────────────────────────────────────────────────────

    public CAPIEventLog forward(
            Tenant tenant,
            String ghlEventType,
            String ghlContactId,
            String sessionId,
            String email,
            String phone,
            Double dealValue,
            String clientEventId
    ) {
        Optional<ProxyMapping> mapping = qualityGate(ghlEventType);
        if (mapping.isEmpty()) {
            return saveSkipped(tenant.getId(), ghlEventType, ghlContactId, sessionId);
        }

        ProxyMapping pm = mapping.get();
        float avgDeal = avgDealValue(tenant);
        float proxyValue = "Purchase".equals(pm.metaEventName()) && dealValue != null
                ? dealValue.floatValue()
                : avgDeal * pm.multiplier();

        String pixelId      = pixelRepo.findDefaultPixelId(tenant.getId()).orElse(null);
        String accessToken  = pixelRepo.findDefaultCapiTokenBytes(tenant.getId()).map(vault::openOrNull).orElse(null);

        // Lookup session external_id_hash for Advanced Matching (vision §7.4)
        String externalIdHash = lookupExternalIdHash(tenant.getId(), sessionId);
        Map<String, Object> payload = buildPayload(
                pm.metaEventName(), email, phone, proxyValue, clientEventId, externalIdHash);

        CAPIEventLog entry = new CAPIEventLog();
        entry.setTenantId(tenant.getId());
        entry.setSessionId(sessionId);
        entry.setEventName(pm.metaEventName());
        entry.setEventId(clientEventId);
        entry.setPixelId(pixelId);
        entry.setProxyValue(proxyValue);
        entry.setGhlEventType(ghlEventType);
        entry.setGhlContactId(ghlContactId);
        entry.setPayload(toJson(payload));

        if (pixelId == null || accessToken == null) {
            entry.setStatus("PENDING");
            log.info("CAPI queued as PENDING (no credentials) — tenant={} event={}",
                    tenant.getId(), pm.metaEventName());
            return capiRepo.save(entry);
        }

        return sendToMeta(entry, pixelId, accessToken, payload);
    }

    // ─── Meta Graph API call ─────────────────────────────────────────────────

    private CAPIEventLog sendToMeta(
            CAPIEventLog entry, String pixelId, String accessToken,
            Map<String, Object> payload) {
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setLastAttemptAt(Instant.now());
        try {
            // Token travels in the POST body, not the query string (audit §3.4: it leaked into proxy/CDN logs).
            String url = GRAPH_API + "/" + pixelId + "/events";
            Map<String, Object> body = Map.of("data", List.of(payload), "access_token", accessToken);

            ResponseEntity<String> resp = restTemplate.postForEntity(url, body, String.class);
            entry.setStatus("SENT");
            entry.setSentAt(Instant.now());
            entry.setMetaResponse(resp.getBody());
            log.info("CAPI sent: event={} pixel={} value={} tenant={}",
                    entry.getEventName(), pixelId, entry.getProxyValue(), entry.getTenantId());
        } catch (Exception e) {
            entry.setStatus("FAILED");
            entry.setMetaResponse("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            log.warn("CAPI send failed for tenant {}: {}", entry.getTenantId(), e.getMessage());
        }
        return capiRepo.save(entry);
    }

    // ─── Payload builder ─────────────────────────────────────────────────────

    private Map<String, Object> buildPayload(
            String eventName, String email, String phone,
            float value, String eventId) {
        return buildPayload(eventName, email, phone, value, eventId, null);
    }

    private Map<String, Object> buildPayload(
            String eventName, String email, String phone,
            float value, String eventId, String externalIdHash) {

        Map<String, Object> userData = new LinkedHashMap<>();
        if (email != null && !email.isBlank())
            userData.put("em", List.of(sha256(email.toLowerCase().trim())));
        if (phone != null && !phone.isBlank())
            userData.put("ph", List.of(sha256(normalizePhone(phone))));
        // Advanced Matching: external_id from cross-device identity (vision §7.4)
        if (externalIdHash != null && !externalIdHash.isBlank())
            userData.put("external_id", List.of(externalIdHash));

        Map<String, Object> customData = new LinkedHashMap<>();
        customData.put("value", value);
        customData.put("currency", "USD");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_name", eventName);
        event.put("event_time", Instant.now().getEpochSecond());
        event.put("action_source", "system_generated");
        event.put("user_data", userData);
        event.put("custom_data", customData);
        if (eventId != null) event.put("event_id", eventId);

        return event;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private CAPIEventLog saveSkipped(UUID tenantId, String ghlEventType,
                                      String ghlContactId, String sessionId) {
        CAPIEventLog entry = new CAPIEventLog();
        entry.setTenantId(tenantId);
        entry.setSessionId(sessionId);
        entry.setGhlEventType(ghlEventType);
        entry.setGhlContactId(ghlContactId);
        entry.setEventName("NONE");
        entry.setStatus("SKIPPED");
        log.debug("CAPI skipped for GHL event '{}' (quality gate)", ghlEventType);
        return capiRepo.save(entry);
    }

    private float avgDealValue(Tenant tenant) {
        try {
            if (tenant.getSettings() != null) {
                var node = MAPPER.readTree(tenant.getSettings());
                if (node.has("avg_deal_value")) {
                    return (float) node.get("avg_deal_value").asDouble(5000.0);
                }
            }
        } catch (Exception ignored) {}
        return 5000f;
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    private static String normalizePhone(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    /** Lookup external_id_hash from sessions for Advanced Matching (vision §7.4). */
    private String lookupExternalIdHash(UUID tenantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            return jdbc.queryForObject("""
                    SELECT external_id_hash
                    FROM sessions
                    WHERE id = ?
                      AND tenant_id = ?
                      AND consent_level = 'full'
                    LIMIT 1
                    """, String.class, sessionId, tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }

    // ─── CAPI Chain v2: DB-driven stage forwarding (vision §19.4) ───────────

    /**
     * Forward CAPI event using the DB-driven capi_event_map for a given stage_key.
     * Evaluates send_condition and picks the correct pixel_role.
     * Non-accredited leads → future_regcf pixel only (NEVER investor_primary).
     */
    public List<CAPIEventLog> forwardByStage(
            Tenant tenant,
            String stageKey,
            String ghlContactId,
            String sessionId,
            String email,
            String phone,
            Boolean selfAccredited,
            String ticketBucket,
            Double monetaryValue,
            String clientEventId,
            ValueFormulaService valueFormulaService) {

        List<Map<String, Object>> mappings = jdbc.queryForList(
                "SELECT pixel_role, meta_event_name, value_formula, send_condition " +
                "FROM capi_event_map WHERE tenant_id = ? AND stage_key = ?",
                tenant.getId(), stageKey);

        List<CAPIEventLog> results = new ArrayList<>();
        for (Map<String, Object> mapping : mappings) {
            String sendCondition = (String) mapping.get("send_condition");
            if (!evaluateCondition(sendCondition, selfAccredited)) {
                log.debug("CAPI stage={} pixel_role={} skipped by condition='{}'",
                        stageKey, mapping.get("pixel_role"), sendCondition);
                continue;
            }

            String formula = (String) mapping.get("value_formula");
            double proxyValue;
            if ("monetaryValue".equals(formula) && monetaryValue != null) {
                proxyValue = monetaryValue;
            } else if ("FIXED".equals(capiValueModeService.effectiveModeForSession(tenant.getId(), sessionId))) {
                // roadmap 9-9 / arch §15.19: FIXED arm = base value with every multiplier at 1
                proxyValue = valueFormulaService.computeValue(tenant.getId(), formula, Boolean.TRUE, null, null);
            } else {
                proxyValue = valueFormulaService.computeValue(
                        tenant.getId(), formula, selfAccredited, ticketBucket, sessionId);   // 9-7: predicted_value
            }

            String roleKey  = (String) mapping.get("pixel_role");
            String pixelId  = pixelRepo.findPixelIdByRole(tenant.getId(), roleKey).orElse(null);
            String accessToken = pixelRepo.findCapiTokenBytesByRole(tenant.getId(), roleKey).map(vault::openOrNull).orElse(null);

            String externalIdHash = lookupExternalIdHash(tenant.getId(), sessionId);
            String metaEventName  = (String) mapping.get("meta_event_name");

            Map<String, Object> payload = buildPayload(
                    metaEventName, email, phone, (float) proxyValue, clientEventId, externalIdHash);

            CAPIEventLog entry = new CAPIEventLog();
            entry.setTenantId(tenant.getId());
            entry.setSessionId(sessionId);
            entry.setEventName(metaEventName);
            entry.setEventId(clientEventId);
            entry.setPixelId(pixelId);
            entry.setProxyValue((float) proxyValue);
            entry.setPixelRole(roleKey);
            entry.setGhlEventType(stageKey);
            entry.setGhlContactId(ghlContactId);
            entry.setPayload(toJson(payload));

            if (pixelId == null || accessToken == null) {
                entry.setStatus("PENDING");
                results.add(capiRepo.save(entry));
                log.info("CAPI v2 queued PENDING stage={} role={} tenant={}",
                        stageKey, roleKey, tenant.getId());
                continue;
            }

            results.add(sendToMeta(entry, pixelId, accessToken, payload));
        }
        return results;
    }

    /**
     * Evaluate a simple send_condition expression.
     * Supported: "self_accredited = true", "self_accredited = false", null/blank = always send.
     */
    static boolean evaluateCondition(String condition, Boolean selfAccredited) {
        if (condition == null || condition.isBlank()) return true;
        String cond = condition.trim().toLowerCase();
        if (cond.equals("self_accredited = true"))  return Boolean.TRUE.equals(selfAccredited);
        if (cond.equals("self_accredited = false")) return Boolean.FALSE.equals(selfAccredited);
        // Audit 4.6: an unknown expression (e.g. a typo in capi_event_map) must not silently disable the gate.
        log.warn("CAPI send_condition '{}' is not supported — event NOT sent (fail closed)", condition);
        return false;
    }

    /** Audit 4.4: sha256(tenant | GHL anchor id | stage) — stable across webhook retries; null without an anchor. */
    public static String deterministicEventId(UUID tenantId, String anchorId, String stageKey) {
        if (anchorId == null || anchorId.isBlank()) return null;
        return sha256(tenantId + "|" + anchorId.trim() + "|" + stageKey);
    }

    // ─── Retry of PENDING / FAILED events (audit 4.5) ────────────────────────

    static final int MAX_ATTEMPTS = 5;
    private static final Duration RETRY_BASE = Duration.ofMinutes(5);
    private static final Duration RETRY_CAP  = Duration.ofHours(6);

    /** Exponential backoff: due when now >= last_attempt + min(5 min · 2^attempts, 6 h). */
    static boolean retryDue(int attempts, Instant lastAttempt, Instant now) {
        if (lastAttempt == null) return true;
        long factor = 1L << Math.min(attempts, 20);
        Duration backoff = RETRY_BASE.multipliedBy(factor);
        if (backoff.compareTo(RETRY_CAP) > 0) backoff = RETRY_CAP;
        return !now.isBefore(lastAttempt.plus(backoff));
    }

    /**
     * Resend PENDING/FAILED events younger than 7 days (Meta's event_time limit) whose backoff has
     * elapsed. Credentials are resolved now (they may have been pasted after the event was logged).
     * Returns the number of events that reached SENT.
     */
    @SuppressWarnings("unchecked")
    public int retryPending(int limit) {
        Instant now = Instant.now();
        List<CAPIEventLog> due = capiRepo.findRetryable(now.minus(Duration.ofDays(7)), MAX_ATTEMPTS, PageRequest.of(0, limit));
        int sent = 0;
        for (CAPIEventLog entry : due) {
            if (!retryDue(entry.getAttempts(), entry.getLastAttemptAt(), now)) continue;
            UUID tenantId = entry.getTenantId();
            String role = entry.getPixelRole();
            String pixelId = role != null
                    ? pixelRepo.findPixelIdByRole(tenantId, role).orElse(null)
                    : pixelRepo.findDefaultPixelId(tenantId).orElse(null);
            String token = role != null
                    ? pixelRepo.findCapiTokenBytesByRole(tenantId, role).map(vault::openOrNull).orElse(null)
                    : pixelRepo.findDefaultCapiTokenBytes(tenantId).map(vault::openOrNull).orElse(null);
            if (pixelId == null || token == null) continue; // still no credentials — keep waiting
            Map<String, Object> payload;
            try {
                payload = MAPPER.readValue(entry.getPayload(), Map.class);
            } catch (Exception e) {
                log.warn("CAPI retry: unreadable payload for event {}: {}", entry.getId(), e.getMessage());
                continue;
            }
            entry.setPixelId(pixelId);
            if ("SENT".equals(sendToMeta(entry, pixelId, token, payload).getStatus())) sent++;
        }
        return sent;
    }
}
