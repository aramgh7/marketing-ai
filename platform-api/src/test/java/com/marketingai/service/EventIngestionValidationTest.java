package com.marketingai.service;

import com.marketingai.dto.event.EventDto;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit §3.4: /events is public and used to take tenant_id, variant_id, creative_id and revenue
 * verbatim from the browser. The tenant is now resolved server-side from the variant/creative.
 */
class EventIngestionValidationTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();
    private static final UUID V1 = UUID.randomUUID();
    private static final UUID C1 = UUID.randomUUID();

    private static final Map<UUID, UUID> VARIANTS  = Map.of(V1, T1);
    private static final Map<UUID, UUID> CREATIVES = Map.of(C1, T1);
    private static final Set<UUID> TENANTS = Set.of(T1, T2);

    private static EventDto ev(UUID tenant, UUID variant, UUID creative, String type) {
        EventDto e = new EventDto();
        e.setEventId("evt-" + java.util.UUID.randomUUID());   // roadmap 9-6: event_id is mandatory
        e.setTenantId(tenant);
        e.setVariantId(variant);
        e.setCreativeId(creative);
        e.setEventType(type);
        e.setSessionId("s");
        return e;
    }

    @Test
    void variantOwnedByTenant_accepted() {
        assertThat(EventIngestionService.rejectReason(ev(T1, V1, null, "PAGE_VIEW"), VARIANTS, CREATIVES, TENANTS)).isNull();
    }

    @Test
    void variantOwnedByOtherTenant_rejected() {
        assertThat(EventIngestionService.rejectReason(ev(T2, V1, null, "PAGE_VIEW"), VARIANTS, CREATIVES, TENANTS))
                .isEqualTo("variant_tenant_mismatch");
    }

    @Test
    void unknownVariant_rejected() {
        assertThat(EventIngestionService.rejectReason(ev(T1, UUID.randomUUID(), null, "PAGE_VIEW"), VARIANTS, CREATIVES, TENANTS))
                .isEqualTo("unknown_variant");
    }

    @Test
    void creativeFallbackChecked() {
        assertThat(EventIngestionService.rejectReason(ev(T1, null, C1, "CLICK"), VARIANTS, CREATIVES, TENANTS)).isNull();
        assertThat(EventIngestionService.rejectReason(ev(T2, null, C1, "CLICK"), VARIANTS, CREATIVES, TENANTS))
                .isEqualTo("creative_tenant_mismatch");
    }

    @Test
    void noIds_requiresKnownTenant() {
        assertThat(EventIngestionService.rejectReason(ev(T2, null, null, "PAGE_VIEW"), VARIANTS, CREATIVES, TENANTS)).isNull();
        assertThat(EventIngestionService.rejectReason(ev(UUID.randomUUID(), null, null, "PAGE_VIEW"), VARIANTS, CREATIVES, TENANTS))
                .isEqualTo("unknown_tenant");
    }

    @Test
    void serverOnlyEventTypes_rejectedFromBrowser() {
        for (String t : new String[]{"PURCHASE", "CONVERSION", "LEAD", "IMPRESSION", "CRM_DEAL_WON"}) {
            assertThat(EventIngestionService.rejectReason(ev(T1, V1, null, t), VARIANTS, CREATIVES, TENANTS))
                    .as(t).isEqualTo("server_only_event_type");
        }
    }

    @Test
    void clientRevenueIsDropped() {
        EventDto e = ev(T1, V1, null, "FORM_SUBMIT");
        e.setRevenue(99999f);
        EventIngestionService.sanitize(e);
        assertThat(e.getRevenue()).isNull();
    }
}
