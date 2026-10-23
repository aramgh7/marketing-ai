package com.marketingai.scheduler;

import com.marketingai.service.ChurnPenaltyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily retroactive β penalty for Thompson arms with post-conversion churn (vision §6.7). */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChurnPenaltyScheduler {

    private final ChurnPenaltyService churnPenaltyService;

    /** 03:00 UTC daily — off-peak, after GHL sync cycle completes. */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void runDailyScan() {
        log.info("Churn penalty scan started");
        try {
            churnPenaltyService.runScan();
        } catch (Exception e) {
            log.error("Churn penalty scan failed: {}", e.getMessage(), e);
        }
        log.info("Churn penalty scan completed");
    }
}
