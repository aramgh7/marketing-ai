package com.marketingai.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit 4.1: resolveStage matched funnel_stages.maps_to_ghl_status against the raw GHL type
 * ("AppointmentUpdate") while seeds store composite keys ("AppointmentUpdate_showed"), so
 * CALL_HELD and FUNDS_RECEIVED could never be reached.
 */
class FunnelServiceTest {

    @Test
    void appointmentStatusBuildsCompositeKeyFirst() {
        assertThat(FunnelService.candidateKeys("AppointmentUpdate", null, "showed"))
                .containsExactly("AppointmentUpdate_showed", "AppointmentUpdate");
    }

    @Test
    void opportunityStatusBuildsCompositeKey() {
        assertThat(FunnelService.candidateKeys("OpportunityStatusUpdate", "won", null))
                .containsExactly("OpportunityStatusUpdate_won", "OpportunityStatusUpdate");
    }

    @Test
    void statusIsLowerCased() {
        assertThat(FunnelService.candidateKeys("AppointmentUpdate", null, "NoShow"))
                .first().isEqualTo("AppointmentUpdate_noshow");
    }

    @Test
    void bareTypeWhenNoStatus() {
        assertThat(FunnelService.candidateKeys("AppointmentCreate", null, null))
                .containsExactly("AppointmentCreate");
    }

    @Test
    void bothStatusesPresent_bothCompositesTried() {
        assertThat(FunnelService.candidateKeys("AppointmentUpdate", "open", "showed"))
                .containsExactly("AppointmentUpdate_showed", "AppointmentUpdate_open", "AppointmentUpdate");
    }
}
