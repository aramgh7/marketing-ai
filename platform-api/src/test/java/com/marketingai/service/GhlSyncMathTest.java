package com.marketingai.service;

import com.marketingai.service.GhlSyncMath.Action;
import com.marketingai.service.GhlSyncMath.RemoteAppointment;
import com.marketingai.service.GhlSyncMath.SyncAction;
import com.marketingai.service.GhlSyncMath.WonDeal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-8: GHL polling reconciliation — diff remote vs local without touching the DB. */
class GhlSyncMathTest {

    private static final Instant T = Instant.parse("2026-10-21T15:00:00Z");

    @Test
    void showed_on_scheduled_local_becomes_complete() {
        List<SyncAction> plan = GhlSyncMath.planAppointmentUpdates(
                List.of(new RemoteAppointment("a1", "c1", T, "showed")), Map.of("a1", "SCHEDULED"));
        assertThat(plan).extracting(SyncAction::action).containsExactly(Action.COMPLETE);
    }

    @Test
    void noshow_and_cancel_map_and_resolved_local_is_left_alone() {
        List<SyncAction> plan = GhlSyncMath.planAppointmentUpdates(List.of(
                new RemoteAppointment("a1", "c1", T, "noshow"),
                new RemoteAppointment("a2", "c2", T, "cancelled"),
                new RemoteAppointment("a3", "c3", T, "showed")),
                Map.of("a1", "SCHEDULED", "a2", "SCHEDULED", "a3", "NO_SHOW"));
        assertThat(plan).extracting(SyncAction::appointmentId).containsExactly("a1", "a2");
        assertThat(plan).extracting(SyncAction::action).containsExactly(Action.NO_SHOW, Action.CANCEL);
    }

    @Test
    void unknown_remote_booking_is_registered_then_resolved() {
        List<SyncAction> plan = GhlSyncMath.planAppointmentUpdates(List.of(
                new RemoteAppointment("new1", "c9", T, "confirmed"),
                new RemoteAppointment("new2", "c8", T, "showed"),
                new RemoteAppointment("gone", "c7", T, "cancelled")), Map.of());
        assertThat(plan).extracting(SyncAction::action)
                .containsExactly(Action.REGISTER, Action.REGISTER, Action.COMPLETE);
        assertThat(plan).extracting(SyncAction::appointmentId).doesNotContain("gone");
    }

    @Test
    void blank_ids_and_unknown_statuses_are_ignored() {
        List<SyncAction> plan = GhlSyncMath.planAppointmentUpdates(List.of(
                new RemoteAppointment("", "c1", T, "showed"),
                new RemoteAppointment("a1", "c1", T, "rescheduled")), Map.of("a1", "SCHEDULED"));
        assertThat(plan).isEmpty();
        assertThat(GhlSyncMath.normaliseStatus("Showed")).isEqualTo("COMPLETED");
        assertThat(GhlSyncMath.normaliseStatus("no-show")).isEqualTo("NO_SHOW");
        assertThat(GhlSyncMath.normaliseStatus(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void won_deals_skip_already_converted_contacts() {
        List<WonDeal> plan = GhlSyncMath.planWonDeals(List.of(
                new WonDeal("o1", "c1", 75_000), new WonDeal("o2", "c2", 50_000), new WonDeal("o3", null, 1)),
                Set.of("c1"));
        assertThat(plan).extracting(WonDeal::opportunityId).containsExactly("o2");
    }
}
