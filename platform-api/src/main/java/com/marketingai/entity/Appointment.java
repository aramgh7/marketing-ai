package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "appointments")
@Getter
@Setter
@NoArgsConstructor
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID tenantId;

    private String sessionId;
    private String ghlContactId;
    private String ghlAppointmentId;

    @Column(nullable = false)
    private Instant scheduledAt;

    /** SCHEDULED | COMPLETED | NO_SHOW | CANCELLED */
    @Column(nullable = false)
    private String status = "SCHEDULED";

    /** GHL_WEBHOOK | TIMEOUT */
    private String source;

    private Instant ghlUpdatedAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
