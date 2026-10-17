package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * CAPI Chain v2 value formula evaluator (vision §19.4 / arch §16.4).
 * Computes proxy values using k_accr × k_ticket multipliers stored in tenants.value_coeffs.
 *
 * Formula tokens: base_lead, base_schedule, base_reg + k_accr + k_ticket
 * e.g. "4000*k_accr*k_ticket" → resolved from coeffs JSONB
 *
 * SECURITY: Spring Boot NEVER writes thompson_arms alpha/beta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValueFormulaService {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Coefficient resolution ───────────────────────────────────────────────

    /**
     * Resolve k_accr multiplier from session's self_accredited field.
     *   true  → 1.0
     *   false → 0.0 (non-accredited; route to future_regcf pixel, NOT investor pixel)
     *   null  → 0.5 (unknown; conservative default)
     */
    public static double resolveKAccr(Boolean selfAccredited) {
        if (selfAccredited == null) return 0.5;
        return selfAccredited ? 1.0 : 0.0;
    }

    /**
     * Resolve k_ticket from ticket_bucket using tenants.value_coeffs.
     * Default scale: LT25K=0.3, 25_50K=0.6, 50_100K=1.0, 100_250K=1.8, GT250K=3.0
     */
    public double resolveKTicket(UUID tenantId, String ticketBucket) {
        JsonNode coeffs = loadValueCoeffs(tenantId);
        if (coeffs == null || ticketBucket == null) return 1.0;
        JsonNode kTicketNode = coeffs.path("k_ticket");
        if (kTicketNode.isMissingNode()) return 1.0;
        return kTicketNode.path(ticketBucket).asDouble(1.0);
    }

    /**
     * Compute CAPI proxy value from formula string + session qualifiers.
     *
     * Supported token substitutions:
     *   base_lead     → coeffs.base_lead
     *   base_schedule → coeffs.base_schedule
     *   base_reg      → coeffs.base_reg
     *   k_accr        → resolved from selfAccredited
     *   k_ticket      → resolved from ticketBucket
     *
     * Arithmetic: only * supported (all formulas are multiplicative).
     */
    public double computeValue(
            UUID tenantId,
            String formula,
            Boolean selfAccredited,
            String ticketBucket) {

        if (formula == null || formula.isBlank()) return 0.0;

        JsonNode coeffs = loadValueCoeffs(tenantId);
        double baseLead     = coeffs != null ? coeffs.path("base_lead").asDouble(4000)     : 4000;
        double baseSchedule = coeffs != null ? coeffs.path("base_schedule").asDouble(7600)  : 7600;
        double baseReg      = coeffs != null ? coeffs.path("base_reg").asDouble(15800)      : 15800;

        double kAccr   = resolveKAccr(selfAccredited);
        double kTicket = resolveKTicket(tenantId, ticketBucket);

        String expr = formula.trim()
                .replace("base_lead",     String.valueOf(baseLead))
                .replace("base_schedule", String.valueOf(baseSchedule))
                .replace("base_reg",      String.valueOf(baseReg))
                .replace("k_accr",        String.valueOf(kAccr))
                .replace("k_ticket",      String.valueOf(kTicket));

        return evalMultiplicative(expr);
    }

    /** Evaluate a pure-multiplicative expression like "4000.0*1.0*1.8". */
    private static double evalMultiplicative(String expr) {
        try {
            String[] tokens = expr.split("\\*");
            double result = 1.0;
            for (String t : tokens) {
                result *= Double.parseDouble(t.trim());
            }
            return result;
        } catch (NumberFormatException e) {
            log.warn("Value formula parse error: {}", expr);
            return 0.0;
        }
    }

    // ─── Investor CAPI map seed ───────────────────────────────────────────────

    /**
     * Seed capi_event_map for the investor funnel (InteliGel Reg D 506(c)).
     * Non-accredited → future_regcf pixel ONLY (§19.4 critical rule).
     */
    @Transactional
    public void seedInvestorCAPIMap(UUID tenantId) {
        Object[][] entries = {
            // stage_key,          pixel_role,        meta_event_name,       value_formula,                   send_condition
            {"BOOKED",             "investor_primary", "Lead",                "base_lead*k_accr*k_ticket",      "self_accredited = true"},
            {"BOOKED",             "future_regcf",    "Lead",                "base_lead*0.3",                  "self_accredited = false"},
            {"CALL_HELD",          "investor_primary", "Schedule",            "base_schedule*k_accr*k_ticket",  "self_accredited = true"},
            {"ACCRED_VERIFIED",    "investor_primary", "CompleteRegistration","base_reg*k_ticket",              "self_accredited = true"},
            {"INVEST_COMMITTED",   "investor_primary", "Purchase",            "monetaryValue",                  "self_accredited = true"},
        };

        for (Object[] e : entries) {
            jdbc.update("""
                INSERT INTO capi_event_map
                    (tenant_id, stage_key, pixel_role, meta_event_name, value_formula, send_condition)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, stage_key, pixel_role) DO UPDATE
                    SET meta_event_name = EXCLUDED.meta_event_name,
                        value_formula   = EXCLUDED.value_formula,
                        send_condition  = EXCLUDED.send_condition
                """,
                tenantId, e[0], e[1], e[2], e[3], e[4]);
        }
        log.info("Investor CAPI map seeded for tenant={}", tenantId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private JsonNode loadValueCoeffs(UUID tenantId) {
        try {
            String json = jdbc.queryForObject(
                    "SELECT value_coeffs FROM tenants WHERE id = ?", String.class, tenantId);
            return json != null ? MAPPER.readTree(json) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ─── B2B CAPI map seed ────────────────────────────────────────────────────

    /**
     * Seed capi_event_map for the B2B template (legacy proxy-value bidding).
     */
    @Transactional
    public void seedB2BCAPIMap(UUID tenantId) {
        Object[][] entries = {
            {"CALL_ANSWERED",   "investor_primary", "Lead",     "base_lead",       null},
            {"MEETING_ATTENDED","investor_primary", "Schedule", "base_schedule",   null},
            {"DEAL_WON",        "investor_primary", "Purchase", "monetaryValue",   null},
        };
        for (Object[] e : entries) {
            jdbc.update("""
                INSERT INTO capi_event_map
                    (tenant_id, stage_key, pixel_role, meta_event_name, value_formula, send_condition)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, stage_key, pixel_role) DO NOTHING
                """,
                tenantId, e[0], e[1], e[2], e[3], e[4]);
        }
    }

    public Map<String, Double> getCAPIMap(UUID tenantId) {
        var rows = jdbc.queryForList(
                "SELECT stage_key, value_formula FROM capi_event_map WHERE tenant_id = ?", tenantId);
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        rows.forEach(r -> result.put((String) r.get("stage_key"), 0.0));
        return result;
    }
}
