package com.marketingai.scheduler;

import com.marketingai.service.FraudScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudReviewScheduler {

    private final FraudScoringService fraudService;

    /**
     * Hourly retrospective fraud review (architecture.md §9 scheduled jobs).
     * Checks session duration and IP clustering for sessions completed in the past 2 hours.
     * Fires Telegram alert if tenant-level fraud rate exceeds 10%.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void reviewFraud() {
        log.debug("Fraud review started");
        try {
            fraudService.reviewRecentSessions();
        } catch (Exception e) {
            log.error("Fraud review failed", e);
        }
        log.debug("Fraud review completed");
    }
}
