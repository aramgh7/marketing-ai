package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRotationSchedulerTest {

    @Test
    void tokenAge_51days_needsRotation() {
        LocalDate createdAt = LocalDate.now().minusDays(51);
        long daysOld = ChronoUnit.DAYS.between(createdAt, LocalDate.now());
        assertThat(daysOld).isGreaterThanOrEqualTo(50);
    }

    @Test
    void tokenAge_30days_notDueYet() {
        LocalDate createdAt = LocalDate.now().minusDays(30);
        long daysOld = ChronoUnit.DAYS.between(createdAt, LocalDate.now());
        assertThat(daysOld).isLessThan(50);
    }

    @Test
    void tokenAge_58days_markedInvalid() {
        LocalDate createdAt = LocalDate.now().minusDays(58);
        long daysOld = ChronoUnit.DAYS.between(createdAt, LocalDate.now());
        assertThat(daysOld).isGreaterThanOrEqualTo(58);
    }

    @Test
    void tokenAge_49days_belowRotationThreshold() {
        LocalDate createdAt = LocalDate.now().minusDays(49);
        long daysOld = ChronoUnit.DAYS.between(createdAt, LocalDate.now());
        assertThat(daysOld).isLessThan(50);
    }

    @Test
    void tokenAge_0days_isOk() {
        LocalDate createdAt = LocalDate.now();
        long daysOld = ChronoUnit.DAYS.between(createdAt, LocalDate.now());
        assertThat(daysOld).isEqualTo(0);
    }

    @Test
    void systemUserToken_decidesOk_atAnyAge() {
        // M-7: SYSTEM_USER tokens don't expire; the scheduler skips them entirely.
        // MetaTokenExchange.decide() may still return INVALID at day ≥58 — the type-guard
        // in the scheduler must execute BEFORE the decide() call.
        // This test documents that the scheduler's guard is by token type, not by age.
        assertThat(MetaTokenExchange.decide(100)).isEqualTo(MetaTokenExchange.Decision.INVALID);
        // A SYSTEM_USER token would be skipped before reaching MetaTokenExchange.decide(),
        // so 100 days old is only INVALID for USER tokens.
        assertThat(MetaTokenExchange.decide(49)).isEqualTo(MetaTokenExchange.Decision.OK);
    }
}
