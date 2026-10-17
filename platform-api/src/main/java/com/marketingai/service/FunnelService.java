package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Funnel as Configuration (vision §19.3 / arch §16.3).
 * Resolves GHL events → funnel stage keys from DB; seeds B2B + Investor templates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FunnelService {

    private final JdbcTemplate jdbc;

    // ─── Stage resolution ─────────────────────────────────────────────────────

    /**
     * Resolve a GHL event type to a funnel stage_key for the given tenant.
     * Falls back to empty string if unmapped.
     */
    public String resolveStage(UUID tenantId, String ghlEventType) {
        try {
            return jdbc.queryForObject(
                    "SELECT stage_key FROM funnel_stages WHERE tenant_id = ? AND maps_to_ghl_status = ? LIMIT 1",
                    String.class, tenantId, ghlEventType);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the stage_key marked is_quality_key=true (first one if multiple).
     * Used by CAPI to identify when to fire the Lead/Schedule events.
     */
    public Optional<String> getQualityStageKey(UUID tenantId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT stage_key FROM funnel_stages WHERE tenant_id = ? AND is_quality_key = true ORDER BY ordinal LIMIT 1",
                    String.class, tenantId));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Return all funnel stages for a tenant ordered by ordinal.
     */
    public List<Map<String, Object>> getStages(UUID tenantId) {
        return jdbc.queryForList(
                "SELECT stage_key, ordinal, label, is_quality_key, is_terminal, " +
                "maps_to_ghl_status, maps_to_event_type FROM funnel_stages " +
                "WHERE tenant_id = ? ORDER BY ordinal",
                tenantId);
    }

    // ─── Template seeds ───────────────────────────────────────────────────────

    /**
     * Seed default B2B funnel template (FORM_SUBMIT → CALL_ANSWERED → MEETING_ATTENDED → DEAL_WON).
     * Idempotent: skips stages that already exist.
     */
    @Transactional
    public void seedB2BTemplate(UUID tenantId) {
        List<Object[]> stages = List.of(
            new Object[]{"FORM_SUBMIT",     1, "Form Submitted",      true,  false, "AppointmentCreate",          "FORM_SUBMIT"},
            new Object[]{"CALL_ANSWERED",   2, "Call Answered",       true,  false, "AppointmentUpdate_showed",   "CALL_ANSWERED"},
            new Object[]{"MEETING_ATTENDED",3, "Meeting Attended",    false, false, "AppointmentUpdate_completed","MEETING_ATTENDED"},
            new Object[]{"DEAL_WON",        4, "Deal Won",            false, true,  "OpportunityStatusUpdate_won","DEAL_WON"}
        );
        insertStages(tenantId, stages);
        log.info("B2B funnel template seeded for tenant={}", tenantId);
    }

    /**
     * Seed investor funnel template for InteliGel Reg D 506(c).
     * Stage keys from vision §19.3.
     */
    @Transactional
    public void seedInvestorFunnel(UUID tenantId) {
        List<Object[]> stages = List.of(
            new Object[]{"CALENDAR_OPEN",      1,  "Calendar Opened",          false, false, "CALENDAR_OPEN",         "CALENDAR_OPEN"},
            new Object[]{"SLOT_SELECTED",      2,  "Slot Selected",            false, false, "SLOT_SELECTED",         "SLOT_SELECTED"},
            new Object[]{"FORM_SUBMIT",        3,  "Form Submitted",           false, false, "AppointmentCreate",     "FORM_SUBMIT"},
            new Object[]{"BOOKED",             4,  "Appointment Booked",       true,  false, "AppointmentCreate",     "BOOKED"},
            new Object[]{"COORD_CONFIRMED",    5,  "Coordinator Confirmed",    false, false, "COORD_CONFIRMED",       "COORD_CONFIRMED"},
            new Object[]{"CALL_HELD",          6,  "Call Held",                false, false, "AppointmentUpdate_showed","CALL_HELD"},
            new Object[]{"NO_SHOW",            6,  "No Show",                  false, false, "AppointmentUpdate_noshow","NO_SHOW"},
            new Object[]{"FOLLOWUP_HELD",      7,  "Follow-up Call Held",      false, false, "FOLLOWUP_HELD",         "FOLLOWUP_HELD"},
            new Object[]{"ACCRED_VERIFIED",    8,  "Accreditation Verified",   false, false, "ACCRED_VERIFIED",       "ACCRED_VERIFIED"},
            new Object[]{"INVEST_COMMITTED",   9,  "Investment Committed",     false, false, "INVEST_COMMITTED",      "INVEST_COMMITTED"},
            new Object[]{"FUNDS_RECEIVED",     10, "Funds Received",           false, true,  "OpportunityStatusUpdate_won","FUNDS_RECEIVED"}
        );
        insertStages(tenantId, stages);
        log.info("Investor funnel template seeded for tenant={}", tenantId);
    }

    private void insertStages(UUID tenantId, List<Object[]> stages) {
        for (Object[] s : stages) {
            jdbc.update("""
                INSERT INTO funnel_stages
                    (tenant_id, stage_key, ordinal, label, is_quality_key, is_terminal,
                     maps_to_ghl_status, maps_to_event_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, stage_key) DO NOTHING
                """,
                tenantId, s[0], s[1], s[2], s[3], s[4], s[5], s[6]);
        }
    }

    /**
     * Upsert a custom funnel stage.
     */
    @Transactional
    public void upsertStage(UUID tenantId, Map<String, Object> stage) {
        jdbc.update("""
            INSERT INTO funnel_stages
                (tenant_id, stage_key, ordinal, label, is_quality_key, is_terminal,
                 maps_to_ghl_status, maps_to_event_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, stage_key) DO UPDATE
                SET ordinal = EXCLUDED.ordinal,
                    label = EXCLUDED.label,
                    is_quality_key = EXCLUDED.is_quality_key,
                    is_terminal = EXCLUDED.is_terminal,
                    maps_to_ghl_status = EXCLUDED.maps_to_ghl_status,
                    maps_to_event_type = EXCLUDED.maps_to_event_type
            """,
            tenantId,
            stage.get("stage_key"),
            stage.get("ordinal"),
            stage.get("label"),
            stage.getOrDefault("is_quality_key", false),
            stage.getOrDefault("is_terminal", false),
            stage.get("maps_to_ghl_status"),
            stage.get("maps_to_event_type"));
    }
}
