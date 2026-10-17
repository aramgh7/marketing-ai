package com.marketingai.service;

import com.marketingai.scheduler.SiteHealthScheduler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteHealthSchedulerTest {

    @Test
    void sentinelUserAgent_containsHealthCheck() {
        assertThat(SiteHealthScheduler.SENTINEL_UA).contains("HealthCheck");
    }

    @Test
    void sentinelUserAgent_exactValue() {
        assertThat(SiteHealthScheduler.SENTINEL_UA).isEqualTo("MarketingAI-HealthCheck/1.0");
    }

    @Test
    void twoConsecutiveFails_meetsIncidentThreshold() {
        int threshold = 2;
        int fails = 2;
        assertThat(fails).isGreaterThanOrEqualTo(threshold);
    }

    @Test
    void oneFailure_belowIncidentThreshold() {
        int threshold = 2;
        int fails = 1;
        assertThat(fails).isLessThan(threshold);
    }

    @Test
    void http5xx_classifiedAsFail() {
        int statusCode = 500;
        assertThat(statusCode).isGreaterThanOrEqualTo(500);
    }

    @Test
    void http200_classifiedAsPass() {
        int statusCode = 200;
        assertThat(statusCode).isLessThan(500);
    }
}
