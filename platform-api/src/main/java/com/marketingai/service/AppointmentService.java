package com.marketingai.service;

import com.marketingai.entity.Appointment;
import com.marketingai.repository.AppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Appointment lifecycle management.
 *
 * State machine:
 *   SCHEDULED → COMPLETED  (GHL AppointmentUpdate appointmentStatus=showed)
 *   SCHEDULED → NO_SHOW    (GHL AppointmentUpdate appointmentStatus=noshow, or 7-day timeout)
 *   SCHEDULED → CANCELLED  (AppointmentUpdate with unhandled status)
 *
 * GHL is the sole source of truth — sales rep manually sets appointmentStatus in GHL CRM.
 * Google Calendar integration is NOT part of MVP (arch v1.1 §13.1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final AppointmentRepository appointmentRepo;
    private final JdbcTemplate jdbc;

    private static final int NO_SHOW_TIMEOUT_DAYS   = 7;
    private static final int CALENDAR_CHECK_DELAY_H = 1;

    // ─── GHL webhook handlers ─────────────────────────────────────────────────

    /**
     * Register a new appointment on GHL AppointmentCreate.
     * Idempotent: skips if ghlAppointmentId already exists for this tenant.
     */
    @Transactional
    public void registerAppointment(UUID tenantId, String sessionId, String contactId,
                                    String ghlAppointmentId, Instant scheduledAt) {
        if (ghlAppointmentId != null &&
                appointmentRepo.findByGhlAppointmentIdAndTenantId(ghlAppointmentId, tenantId).isPresent()) {
            log.debug("Appointment already registered: ghlId={}", ghlAppointmentId);
            return;
        }

        Appointment appt = new Appointment();
        appt.setTenantId(tenantId);
        appt.setSessionId(sessionId);
        appt.setGhlContactId(contactId);
        appt.setGhlAppointmentId(ghlAppointmentId);
        appt.setScheduledAt(scheduledAt != null ? scheduledAt : Instant.now());
        appt.setStatus("SCHEDULED");
        appt.setSource("GHL_WEBHOOK");
        appointmentRepo.save(appt);

        log.info("Appointment registered: tenant={} contact={} at={}", tenantId, contactId, scheduledAt);
    }

    /** GHL appointment.completed → mark attended. */
    @Transactional
    public void markCompleted(UUID tenantId, String ghlAppointmentId, String contactId) {
        Appointment appt = findOrCreatePlaceholder(tenantId, ghlAppointmentId, contactId);
        if (!"SCHEDULED".equals(appt.getStatus())) return;
        appt.setStatus("COMPLETED");
        appt.setGhlUpdatedAt(Instant.now());
        appt.setSource("GHL_WEBHOOK");
        appointmentRepo.save(appt);
        updateSessionOutcome(appt.getSessionId(), tenantId, true);
        log.info("Appointment COMPLETED (GHL): ghlId={} tenant={}", ghlAppointmentId, tenantId);
    }

    /** GHL appointment.no_show → mark no-show. */
    @Transactional
    public void markNoShow(UUID tenantId, String ghlAppointmentId, String contactId) {
        Appointment appt = findOrCreatePlaceholder(tenantId, ghlAppointmentId, contactId);
        if (!"SCHEDULED".equals(appt.getStatus())) return;
        appt.setStatus("NO_SHOW");
        appt.setGhlUpdatedAt(Instant.now());
        appt.setSource("GHL_WEBHOOK");
        appointmentRepo.save(appt);
        updateSessionOutcome(appt.getSessionId(), tenantId, false);
        log.info("Appointment NO_SHOW (GHL): ghlId={} tenant={}", ghlAppointmentId, tenantId);
    }

    /** GHL appointment.cancelled → mark cancelled. */
    @Transactional
    public void markCancelled(UUID tenantId, String ghlAppointmentId, String contactId) {
        findOrCreatePlaceholder(tenantId, ghlAppointmentId, contactId);
        appointmentRepo.findByGhlAppointmentIdAndTenantId(ghlAppointmentId, tenantId)
                .filter(a -> "SCHEDULED".equals(a.getStatus()))
                .ifPresent(a -> {
                    a.setStatus("CANCELLED");
                    a.setGhlUpdatedAt(Instant.now());
                    a.setSource("GHL_WEBHOOK");
                    appointmentRepo.save(a);
                    log.info("Appointment CANCELLED (GHL): ghlId={}", ghlAppointmentId);
                });
    }

    // ─── Scheduler: 7-day no-show timeout ────────────────────────────────────

    /**
     * Called every 30 minutes by AppointmentCheckScheduler.
     * Processes appointments that are past their scheduled time.
     */
    @Transactional
    public void reviewPendingAppointments() {
        // 1h delay: check appointments scheduled at least 1h ago
        Instant cutoff = Instant.now().minus(CALENDAR_CHECK_DELAY_H, ChronoUnit.HOURS);
        List<Appointment> pending = appointmentRepo.findPendingBefore(cutoff);
        if (pending.isEmpty()) return;

        log.info("Reviewing {} pending appointments", pending.size());
        for (Appointment appt : pending) {
            reviewSingle(appt);
        }
    }

    private void reviewSingle(Appointment appt) {
        Instant now = Instant.now();

        // 7-day hard timeout — no Calendar API fallback (arch v1.1 §13.1)
        if (appt.getScheduledAt().isBefore(now.minus(NO_SHOW_TIMEOUT_DAYS, ChronoUnit.DAYS))) {
            appt.setStatus("NO_SHOW");
            appt.setSource("TIMEOUT");
            appointmentRepo.save(appt);
            updateSessionOutcome(appt.getSessionId(), appt.getTenantId(), false);
            log.info("Appointment NO_SHOW (7-day timeout): id={} tenant={}", appt.getId(), appt.getTenantId());
        }
    }

    // ─── Session outcome ───────────────────────────────────────────────────────

    /**
     * Update session when appointment outcome is known.
     * completed=true  → mark session as converted (APPOINTMENT)
     * completed=false → leave converted unchanged (no-show ≠ conversion)
     */
    private void updateSessionOutcome(String sessionId, UUID tenantId, boolean completed) {
        if (sessionId == null) return;
        if (completed) {
            jdbc.update(
                    """
                    UPDATE sessions SET
                        converted       = true,
                        conversion_type = 'APPOINTMENT'
                    WHERE id = ? AND tenant_id = ? AND converted = false
                    """,
                    sessionId, tenantId
            );
        }
        // No-show: nothing to update on sessions (already not converted)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Appointment findOrCreatePlaceholder(UUID tenantId, String ghlAppointmentId, String contactId) {
        if (ghlAppointmentId != null) {
            Optional<Appointment> existing =
                    appointmentRepo.findByGhlAppointmentIdAndTenantId(ghlAppointmentId, tenantId);
            if (existing.isPresent()) return existing.get();
        }
        // outcome arrived before appointment.scheduled — create a placeholder
        Appointment placeholder = new Appointment();
        placeholder.setTenantId(tenantId);
        placeholder.setGhlAppointmentId(ghlAppointmentId);
        placeholder.setGhlContactId(contactId);
        placeholder.setScheduledAt(Instant.now());
        placeholder.setStatus("SCHEDULED");
        return appointmentRepo.save(placeholder);
    }
}
