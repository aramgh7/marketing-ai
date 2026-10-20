package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lead-form submission (investor funnel): stores form_submissions, stamps the session's
 * qualifiers and logs the predicted lead value for calibration.
 *
 * Shared by the authenticated {@code POST /form/submit} and the public landing-page endpoint
 * {@code POST /public/form/submit} (audit 4.9: the landing page posted to an endpoint that did
 * not exist, so no investor submission was ever stored).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FormSubmissionService {

    private final JdbcTemplate jdbc;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final ValueFormulaService valueFormulaService;
    private final GhlApiClient ghlApiClient;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Owner tenant of a landing variant, or null when unknown (public-endpoint validation). */
    public UUID variantOwner(UUID variantId) {
        if (variantId == null) return null;
        List<UUID> rows = jdbc.queryForList(
                "SELECT tenant_id FROM landing_variants WHERE id = ?", UUID.class, variantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public Map<String, Object> submit(UUID tenantId, Map<String, Object> body) {
        // sessions.id is the VARCHAR cookie id — never cast it to uuid (audit §5)
        String sessionId = body.get("session_id") != null ? body.get("session_id").toString() : null;

        Boolean selfAccredited = parseBoolean(body.get("self_accredited"));
        String  ticketBucket   = body.get("ticket_bucket") != null ? body.get("ticket_bucket").toString() : null;

        if (sessionId != null) {
            jdbc.update("""
                UPDATE sessions SET
                    self_accredited = COALESCE(?, self_accredited),
                    ticket_bucket   = COALESCE(?, ticket_bucket),
                    funnel_key      = COALESCE(funnel_key, 'investor')
                WHERE id = ? AND tenant_id = ?
                """, selfAccredited, ticketBucket, sessionId, tenantId);
        }

        UUID submissionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO form_submissions
                (id, tenant_id, session_id, funnel_key, self_accredited, ticket_bucket,
                 first_name, last_name, email, phone, company, utm_source, utm_campaign,
                 raw_payload, consent_level)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """,
            submissionId, tenantId, sessionId,
            str(body.getOrDefault("funnel_key", "investor")),
            selfAccredited, ticketBucket,
            str(body.get("first_name")), str(body.get("last_name")),
            str(body.get("email")), str(body.get("phone")), str(body.get("company")),
            str(body.get("utm_source")), str(body.get("utm_campaign")),
            toJson(body), str(body.getOrDefault("consent_level", "basic")));

        if (selfAccredited != null && ticketBucket != null) {
            String formula = lookupFormula(tenantId, "FORM_SUBMIT");
            double predictedValue = formula != null
                ? valueFormulaService.computeValue(tenantId, formula, selfAccredited, ticketBucket, sessionId)
                : 0.0;
            if (predictedValue > 0) {
                jdbc.update("""
                    INSERT INTO value_calibration_log
                        (tenant_id, session_id, predicted_value, ticket_bucket, self_accredited, stage_key)
                    VALUES (?, ?, ?, ?, ?, 'FORM_SUBMIT')
                    """, tenantId, sessionId, predictedValue, ticketBucket, selfAccredited);
            }
        }

        // Roadmap 8-9: when the GHL contact is already known (GHL-native form), write our ids now —
        // before any sub-account transfer can break the email/phone link.
        String ghlContactId = body.get("ghl_contact_id") != null ? body.get("ghl_contact_id").toString() : null;
        if (ghlContactId != null && !ghlContactId.isBlank()) {
            try {
                jdbc.update("UPDATE form_submissions SET ghl_contact_id = ? WHERE id = ?", ghlContactId, submissionId);
                List<byte[]> keys = jdbc.queryForList(
                        "SELECT api_key_enc FROM ghl_subaccounts WHERE tenant_id = ? AND role = 'MAIN' AND is_active = TRUE AND api_key_enc IS NOT NULL LIMIT 1",
                        byte[].class, tenantId);
                if (!keys.isEmpty()) {
                    ghlApiClient.writeContactIds(vault.open(keys.get(0)),
                            ghlContactId, sessionId, submissionId.toString());
                }
            } catch (Exception e) {
                log.debug("GHL id write skipped: {}", e.getMessage());
            }
        }

        log.debug("Form submitted: submission={} tenant={}", submissionId, tenantId);
        return Map.of("submission_id", submissionId, "status", "accepted");
    }

    /** capi_event_map has no is_active column (audit §5) — the first FORM_SUBMIT mapping wins. */
    private String lookupFormula(UUID tenantId, String stageKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT value_formula FROM capi_event_map WHERE tenant_id = ? AND stage_key = ? " +
                "AND value_formula IS NOT NULL ORDER BY created_at LIMIT 1",
                String.class, tenantId, stageKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static Boolean parseBoolean(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim().toLowerCase();
        if (s.equals("true") || s.equals("on") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("off") || s.equals("0") || s.equals("no")) return false;
        return null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private String toJson(Object obj) {
        try { return MAPPER.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }
}
