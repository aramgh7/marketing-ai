package com.marketingai.scheduler;

import com.marketingai.service.CompetitiveIntelligenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Weekly Ad Library scan for competitor creatives (vision §9.1). */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompetitiveIntelligenceScheduler {

    private final CompetitiveIntelligenceService ciService;

    /** Every Monday at 05:00 UTC — after nightly scans complete. */
    @Scheduled(cron = "0 0 5 * * MON")
    public void runWeeklyScan() {
        log.info("Competitive intelligence scan started");
        try {
            ciService.runWeeklyScan();
        } catch (Exception e) {
            log.error("Competitive intelligence scan failed: {}", e.getMessage(), e);
        }
        log.info("Competitive intelligence scan completed");
    }
}
