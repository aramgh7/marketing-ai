package com.marketingai.dto.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class EventDto {

    /** arch v1.1 §4.5: client-generated UUID for deduplication (renamed from clientEventId). */
    private String eventId;

    @NotBlank
    private String sessionId;

    @NotNull
    private String eventType;

    @NotNull
    private UUID tenantId;

    private UUID creativeId;
    private UUID variantId;

    /** Block identifier, e.g. "blk_001" */
    private String blockEventId;

    /** Arbitrary event payload stored as JSONB. */
    private Map<String, Object> eventData;

    /** Revenue in currency units — required for PURCHASE events. */
    private Float revenue;

    private String device;
    private String geo;
    private String utmSource;
    private String utmMedium;
    private String utmCampaign;

    /** full | analytics_only | none */
    private String consentLevel;

    /** Client-provided timestamp; server uses now() when null. */
    private Instant eventTime;
}
