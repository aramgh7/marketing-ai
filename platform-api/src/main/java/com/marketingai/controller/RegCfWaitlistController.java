package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Reg CF Waitlist (vision §19.18.8; arch §16.20).
 * ⚠️ FROZEN — OI-3: Meta audience NOT activated until regcf_activation_gate = ACTIVE.
 * DO NOT add any code that sends leads to Meta audiences from this controller.
 */
@RestController
@RequestMapping("/regcf-waitlist")
@RequiredArgsConstructor
@Slf4j
public class RegCfWaitlistController {

    private final JdbcTemplate jdbc;
    private final RestTemplate restTemplate;
    private final AppProperties appProperties;

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> join(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String email = (String) body.get("email");
        if (email == null || !email.contains("@"))
            return ResponseEntity.badRequest().body(Map.of("error", "Valid email required"));
        if (!Boolean.TRUE.equals(body.get("consent_given")))
            return ResponseEntity.badRequest().body(Map.of("error", "Explicit consent required"));

        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO regcf_waitlist
                (id, tenant_id, email, ticket_interest, source_session, consent_given, consent_ts)
            VALUES (?, ?, ?, ?, ?, TRUE, NOW())
            ON CONFLICT (tenant_id, email) DO NOTHING
            """, id, tenantId, email,
            body.get("ticket_interest"),
            body.get("session_id") != null ? UUID.fromString((String) body.get("session_id")) : null);

        return ResponseEntity.ok(Map.of(
            "id",   id,
            "note", "Added to Reg CF waitlist. Activation pending OI-3 counsel answer."));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
            SELECT id, email, ticket_interest, consent_given, status, added_at
            FROM regcf_waitlist WHERE tenant_id = ? ORDER BY added_at DESC
            """, tenantId));
    }

    @GetMapping("/gate")
    public ResponseEntity<Map<String, Object>> getGate(
            @RequestAttribute("tenantId") UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT status, counsel_answer, unlocked_at FROM regcf_activation_gate WHERE tenant_id = ?
            """, tenantId);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "status",       "FROZEN",
                "oi_reference", "OI-3",
                "vision_ref",   "§19.16",
                "note",         "OI-3: Awaiting counsel answer before activating non-accredited marketing"));
        }
        Map<String, Object> gate = new HashMap<>(rows.get(0));
        gate.put("oi_reference", "OI-3");
        gate.put("vision_ref",   "§19.16");
        gate.put("note", "REGCF_WAITLIST audience NOT activated until status = ACTIVE + counsel sign-off");
        return ResponseEntity.ok(gate);
    }

    /** Requires counsel answer text. Moves FROZEN → PENDING_REVIEW only. Never auto-activates Meta. */
    @PostMapping("/gate/unlock")
    public ResponseEntity<Map<String, Object>> unlockGate(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {
        String counselAnswer = (String) body.get("counsel_answer");
        if (counselAnswer == null || counselAnswer.isBlank())
            return ResponseEntity.badRequest().body(Map.of(
                "error", "counsel_answer required (OI-3 answer must be documented before unlocking)"));

        jdbc.update("""
            UPDATE regcf_activation_gate
            SET status = 'PENDING_REVIEW', counsel_answer = ?, unlocked_by = ?, unlocked_at = NOW()
            WHERE tenant_id = ? AND status = 'FROZEN'
            """, counselAnswer, body.get("unlocked_by"), tenantId);

        log.info("RegCF gate unlock → PENDING_REVIEW: tenant={} by={}", tenantId, body.get("unlocked_by"));
        return ResponseEntity.ok(Map.of(
            "status", "PENDING_REVIEW",
            "note",   "Gate moved to PENDING_REVIEW. Requires additional approval before ACTIVE. " +
                      "Meta audience remains inactive until ACTIVE status."));
    }

    @GetMapping("/value")
    public ResponseEntity<Map<String, Object>> waitlistValue(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "0.05") double pConvert,
            @RequestParam(defaultValue = "10000") double avgTicket) {

        int nLeads = 0;
        try {
            nLeads = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regcf_waitlist WHERE tenant_id = ? AND status != 'REMOVED'",
                Integer.class, tenantId);
        } catch (Exception e) {
            log.warn("Could not count waitlist leads: {}", e.getMessage());
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                appProperties.getMlServiceUrl() + "/waitlist/value",
                Map.of("n_leads", nLeads, "p_convert_regcf", pConvert, "avg_ticket_regcf", avgTicket),
                Map.class);
            return ResponseEntity.ok(result != null ? result : Map.of("n_leads", nLeads));
        } catch (Exception e) {
            double estimated = nLeads * pConvert * avgTicket;
            return ResponseEntity.ok(Map.of(
                "n_leads", nLeads, "estimated_value", estimated,
                "frozen_note", "OI-3: REGCF_WAITLIST NOT activated"));
        }
    }
}
