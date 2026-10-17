package com.marketingai.scheduler;

import com.marketingai.service.PixelPollutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily pixel pollution drift scan — Level 3 protection (vision §6.8). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PixelPollutionScheduler {

    private final PixelPollutionService pixelPollutionService;

    /** 04:00 UTC daily — after churn scan (03:00) completes. */
    @Scheduled(cron = "0 0 4 * * *")
    public void runDailyScan() {
        log.info("Pixel pollution scan started");
        try {
            pixelPollutionService.runScan();
        } catch (Exception e) {
            log.error("Pixel pollution scan failed: {}", e.getMessage(), e);
        }
        log.info("Pixel pollution scan completed");
    }
}
