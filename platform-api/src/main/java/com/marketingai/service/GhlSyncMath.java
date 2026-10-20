package com.marketingai.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure reconciliation logic for the hourly GHL polling sync (roadmap 9-8; arch §9 "GHL sync").
 * Webhooks are the primary path; polling catches the updates they missed (outage, secret mismatch,
 * manual status set while the platform was down).
 */
public final class GhlSyncMath {

    private GhlSyncMath() {}

    public enum Action { REGISTER, COMPLETE, NO_SHOW, CANCEL }

    /** A remote GHL appointment as returned by /calendars/events (normalised). */
    public record RemoteAppointment(String id, String contactId, Instant startTime, String appointmentStatus) {}

    public record SyncAction(Action action, String appointmentId, String contactId, Instant startTime) {}

    /** A won opportunity to reconcile into sessions (contact → session resolved by the caller). */
    public record WonDeal(String opportunityId, String contactId, double monetaryValue) {}

    private static final Set<String> SHOWED  = Set.of("showed", "completed", "confirmed_showed");
    private static final Set<String> NOSHOW  = Set.of("noshow", "no_show", "no-show");
    private static final Set<String> CANCEL  = Set.of("cancelled", "canceled", "invalid");
    private static final Set<String> BOOKED  = Set.of("confirmed", "booked", "new", "scheduled");

    /**
     * @param remote      appointments from GHL
     * @param localStatus ghl_appointment_id → local appointments.status (SCHEDULED / COMPLETED / NO_SHOW / CANCELLED)
     */
    public static List<SyncAction> planAppointmentUpdates(List<RemoteAppointment> remote,
                                                          Map<String, String> localStatus) {
        List<SyncAction> out = new ArrayList<>();
        for (RemoteAppointment r : remote) {
            if (r.id() == null || r.id().isBlank()) continue;
            String st = r.appointmentStatus() == null ? "" : r.appointmentStatus().trim().toLowerCase(Locale.ROOT);
            String local = localStatus.get(r.id());
            if (local == null) {
                if (BOOKED.contains(st) || SHOWED.contains(st) || NOSHOW.contains(st)) {
                    out.add(new SyncAction(Action.REGISTER, r.id(), r.contactId(), r.startTime()));
                }
                // a never-seen cancelled appointment is noise
                if (SHOWED.contains(st))  out.add(new SyncAction(Action.COMPLETE, r.id(), r.contactId(), r.startTime()));
                if (NOSHOW.contains(st))  out.add(new SyncAction(Action.NO_SHOW,  r.id(), r.contactId(), r.startTime()));
                continue;
            }
            if (!"SCHEDULED".equals(local)) continue;   // local already resolved — webhook won
            if (SHOWED.contains(st))      out.add(new SyncAction(Action.COMPLETE, r.id(), r.contactId(), r.startTime()));
            else if (NOSHOW.contains(st)) out.add(new SyncAction(Action.NO_SHOW,  r.id(), r.contactId(), r.startTime()));
            else if (CANCEL.contains(st)) out.add(new SyncAction(Action.CANCEL,   r.id(), r.contactId(), r.startTime()));
        }
        return out;
    }

    /** Won opportunities whose contact's session is not converted yet. */
    public static List<WonDeal> planWonDeals(List<WonDeal> remoteWon, Set<String> convertedContactIds) {
        List<WonDeal> out = new ArrayList<>();
        for (WonDeal d : remoteWon) {
            if (d.contactId() == null || convertedContactIds.contains(d.contactId())) continue;
            out.add(d);
        }
        return out;
    }

    /** Status normalisation shared with the scheduler log. */
    public static String normaliseStatus(String ghlStatus) {
        String st = ghlStatus == null ? "" : ghlStatus.trim().toLowerCase(Locale.ROOT);
        if (SHOWED.contains(st)) return "COMPLETED";
        if (NOSHOW.contains(st)) return "NO_SHOW";
        if (CANCEL.contains(st)) return "CANCELLED";
        if (BOOKED.contains(st)) return "SCHEDULED";
        return "UNKNOWN";
    }
}
