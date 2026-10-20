package com.marketingai.scheduler;

import com.marketingai.service.CAPIForwardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resends PENDING / FAILED CAPI events (audit 4.5).
 *
 * Events logged before the tenant pasted a CAPI token, or that hit a transient Meta error, sat in
 * capi_events forever: CAPIEventController only counted them. Meta accepts event_time up to 7 days
 * back, so every 10 minutes we retry rows younger than that with exponential backoff
 * (5 min · 2^attempts, max 5 attempts) — see CAPIForwardingService.retryPending.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CapiRetryScheduler {

    private final CAPIForwardingService capiService;

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void retry() {
        try {
            int sent = capiService.retryPending(200);
            if (sent > 0) log.info("CAPI retry: {} events sent", sent);
        } catch (Exception e) {
            log.warn("CAPI retry failed: {}", e.getMessage());
        }
    }
}
