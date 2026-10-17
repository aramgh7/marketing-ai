package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "capi_events")
@Getter
@Setter
@NoArgsConstructor
public class CAPIEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private String sessionId;

    @Column(nullable = false)
    private String eventName;   // Lead, Purchase, Schedule, CompleteRegistration

    private String eventId;     // client_event_id for dedup

    private String pixelId;
    private Float proxyValue;
    private String currency = "USD";

    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload = "{}";  // full CAPI payload

    @Column(columnDefinition = "jsonb")
    private String metaResponse;

    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, SENT, FAILED, SKIPPED

    private String ghlEventType;
    private String ghlContactId;

    private Instant sentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
