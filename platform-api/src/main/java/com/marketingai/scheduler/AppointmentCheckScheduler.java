package com.marketingai.scheduler;

import com.marketingai.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentCheckScheduler {

    private final AppointmentService appointmentService;

    /** Every 30 minutes: apply 7-day no-show timeout to pending appointments. */
    @Scheduled(fixedDelay = 1_800_000)
    public void checkPendingAppointments() {
        log.debug("Appointment check started");
        try {
            appointmentService.reviewPendingAppointments();
        } catch (Exception e) {
            log.error("Appointment check failed: {}", e.getMessage(), e);
        }
        log.debug("Appointment check completed");
    }
}
