package com.marketingai.scheduler;

import com.marketingai.service.AppointmentService;
import com.marketingai.service.GhlApiClient;
import com.marketingai.service.GhlSyncMath;
import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Hourly GHL polling sync (roadmap 9-8; arch §9 "GHL sync — every 60 min").
 * Webhooks stay the primary path; this catches appointment statuses and won deals they missed.
 * Writes go through {@link AppointmentService} (same idempotent transitions as the webhook path) and
 * a guarded UPDATE on sessions for won deals — never to thompson_arms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GhlSyncScheduler {

    static final int LOOKBACK_DAYS = 14;
    static final int LOOKAHEAD_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final GhlApiClient ghlApiClient;
    private final AppointmentService appointmentService;
    private final TokenVault vault;

    @Scheduled(fixedDelay = 60 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void sync() {
        List<Map<String, Object>> locations = jdbc.queryForList("""
                SELECT tenant_id, location_id, api_key_enc
                FROM ghl_subaccounts
                WHERE is_active = TRUE AND role = 'MAIN' AND api_key_enc IS NOT NULL
                """);
        for (Map<String, Object> loc : locations) {
            UUID tenantId = (UUID) loc.get("tenant_id");
            String locationId = (String) loc.get("location_id");
            String apiKey = vault.openOrNull((byte[]) loc.get("api_key_enc"));
            if (apiKey == null) continue;
            try {
                Map<String, Integer> counts = syncLocation(tenantId, locationId, apiKey);
                log.info("GhlSync tenant={} location={} {}", tenantId, locationId, counts);
            } catch (Exception e) {
                log.warn("GhlSync tenant={} location={} failed: {}", tenantId, locationId, e.getMessage());
            }
        }
    }

    /** One location: appointments window [−14 d, +30 d] and won opportunities. Package-private for tests. */
    Map<String, Integer> syncLocation(UUID tenantId, String locationId, String apiKey) {
        Instant now = Instant.now();
        List<GhlSyncMath.RemoteAppointment> remote = ghlApiClient.listAppointments(
                apiKey, locationId, now.minus(LOOKBACK_DAYS, ChronoUnit.DAYS), now.plus(LOOKAHEAD_DAYS, ChronoUnit.DAYS));

        Map<String, String> local = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT ghl_appointment_id, status FROM appointments
                WHERE tenant_id = ? AND ghl_appointment_id IS NOT NULL
                  AND scheduled_at > now() - (? * INTERVAL '1 day')
                """, tenantId, LOOKBACK_DAYS + 1)) {
            local.put((String) r.get("ghl_appointment_id"), (String) r.get("status"));
        }

        int registered = 0, completed = 0, noShow = 0, cancelled = 0;
        for (GhlSyncMath.SyncAction a : GhlSyncMath.planAppointmentUpdates(remote, local)) {
            switch (a.action()) {
                case REGISTER -> { appointmentService.registerAppointment(tenantId, null, a.contactId(), a.appointmentId(),
                        a.startTime() != null ? a.startTime() : now); registered++; }
                case COMPLETE -> { appointmentService.markCompleted(tenantId, a.appointmentId(), a.contactId()); completed++; }
                case NO_SHOW  -> { appointmentService.markNoShow(tenantId, a.appointmentId(), a.contactId()); noShow++; }
                case CANCEL   -> { appointmentService.markCancelled(tenantId, a.appointmentId(), a.contactId()); cancelled++; }
            }
            jdbc.update("UPDATE appointments SET ghl_synced_at = now() WHERE tenant_id = ? AND ghl_appointment_id = ?",
                    tenantId, a.appointmentId());
        }

        // Won opportunities → sessions.converted for contacts whose session is known via an appointment
        List<GhlSyncMath.WonDeal> remoteWon = ghlApiClient.listWonOpportunities(apiKey, locationId);
        Set<String> convertedContacts = new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT a.ghl_contact_id FROM appointments a
                JOIN sessions s ON s.id = a.session_id AND s.tenant_id = a.tenant_id
                WHERE a.tenant_id = ? AND a.ghl_contact_id IS NOT NULL AND s.converted = TRUE
                """, String.class, tenantId));
        int won = 0;
        for (GhlSyncMath.WonDeal d : GhlSyncMath.planWonDeals(remoteWon, convertedContacts)) {
            won += jdbc.update("""
                    UPDATE sessions s SET converted = TRUE, conversion_type = 'DEAL_WON',
                                          revenue = COALESCE(NULLIF(?, 0), s.revenue)
                    WHERE s.tenant_id = ? AND s.converted = FALSE
                      AND s.id = (SELECT a.session_id FROM appointments a
                                  WHERE a.tenant_id = s.tenant_id AND a.ghl_contact_id = ? AND a.session_id IS NOT NULL
                                  ORDER BY a.scheduled_at DESC LIMIT 1)
                    """, (float) d.monetaryValue(), tenantId, d.contactId());
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("remote", remote.size());
        counts.put("registered", registered);
        counts.put("completed", completed);
        counts.put("no_show", noShow);
        counts.put("cancelled", cancelled);
        counts.put("won_deals_applied", won);
        return counts;
    }
}
