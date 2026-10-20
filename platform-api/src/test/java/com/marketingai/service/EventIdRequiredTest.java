package com.marketingai.service;

import com.marketingai.dto.event.EventDto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 9-6: every ingested event must carry the client-generated event_id (dedup key). */
class EventIdRequiredTest {

    private static EventDto event(UUID tenant, String eventId) {
        EventDto e = new EventDto();
        e.setTenantId(tenant);
        e.setEventId(eventId);
        e.setEventType("PAGE_VIEW");
        e.setSessionId("s1");
        return e;
    }

    @Test
    void missing_or_blank_event_id_is_rejected() {
        UUID t = UUID.randomUUID();
        assertThat(EventIngestionService.rejectReason(event(t, null), Map.of(), Map.of(), Set.of(t))).isEqualTo("missing_event_id");
        assertThat(EventIngestionService.rejectReason(event(t, "  "), Map.of(), Map.of(), Set.of(t))).isEqualTo("missing_event_id");
    }

    @Test
    void event_with_id_passes_for_a_known_tenant() {
        UUID t = UUID.randomUUID();
        assertThat(EventIngestionService.rejectReason(event(t, "evt-1"), Map.of(), Map.of(), Set.of(t))).isNull();
    }

    @Test
    void missing_tenant_still_wins_over_missing_event_id() {
        assertThat(EventIngestionService.rejectReason(event(null, null), Map.of(), Map.of(), Set.of())).isEqualTo("missing_tenant");
    }
}
