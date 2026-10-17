package com.marketingai.repository;

import com.marketingai.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByGhlAppointmentIdAndTenantId(String ghlAppointmentId, UUID tenantId);

    /**
     * Pending appointments whose scheduled time is before the given cutoff.
     * Covers both Calendar fallback (1h delay) and 7-day timeout check.
     */
    @Query("SELECT a FROM Appointment a WHERE a.status = 'SCHEDULED' AND a.scheduledAt < :cutoff")
    List<Appointment> findPendingBefore(@Param("cutoff") Instant cutoff);
}
