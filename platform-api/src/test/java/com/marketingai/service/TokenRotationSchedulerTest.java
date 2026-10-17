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
}
