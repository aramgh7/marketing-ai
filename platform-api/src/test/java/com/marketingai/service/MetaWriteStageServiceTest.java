package com.marketingai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Audit M-24: the FULL condition "clean week" counts only DENY decisions initiated by jobs. */
class MetaWriteStageServiceTest {

    @Test
    void clean_week_counts_only_job_denies() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OpeService ope = mock(OpeService.class);
        AutonomyService autonomy = mock(AutonomyService.class);
        CAPIEmqCheckService emq = mock(CAPIEmqCheckService.class);
        UUID tenantId = UUID.randomUUID();
        when(ope.latest(tenantId)).thenReturn(Optional.empty());
        when(autonomy.getAutonomyLevel(tenantId)).thenReturn("ADVISOR");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);

        MetaWriteStageService service = new MetaWriteStageService(jdbc, emq, ope, autonomy);
        List<MetaWriteStageService.Condition> conds = service.conditionsFor(tenantId, "FULL",
                Timestamp.from(Instant.now().minus(Duration.ofDays(20))));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).queryForObject(sql.capture(), eq(Integer.class), eq(tenantId));
        assertThat(sql.getValue()).contains("verdict = 'DENY'").contains("initiator = 'JOB'").contains("7 days");
        assertThat(MetaWriteStageService.CLEAN_WEEK_SQL).contains("initiator = 'JOB'");

        MetaWriteStageService.Condition cleanWeek = conds.stream().filter(c -> c.key().equals("clean_week")).findFirst().orElseThrow();
        assertThat(cleanWeek.passed()).isTrue();
        assertThat(cleanWeek.label()).containsIgnoringCase("job");
    }

    @Test
    void clean_week_fails_when_a_job_write_was_refused() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OpeService ope = mock(OpeService.class);
        AutonomyService autonomy = mock(AutonomyService.class);
        UUID tenantId = UUID.randomUUID();
        when(ope.latest(tenantId)).thenReturn(Optional.empty());
        when(autonomy.getAutonomyLevel(tenantId)).thenReturn("ADVISOR");
        when(jdbc.queryForObject(eq(MetaWriteStageService.CLEAN_WEEK_SQL), eq(Integer.class), any())).thenReturn(2);

        MetaWriteStageService service = new MetaWriteStageService(jdbc, mock(CAPIEmqCheckService.class), ope, autonomy);
        MetaWriteStageService.Condition cleanWeek = service.conditionsFor(tenantId, "FULL", Timestamp.from(Instant.now()))
                .stream().filter(c -> c.key().equals("clean_week")).findFirst().orElseThrow();
        assertThat(cleanWeek.passed()).isFalse();
        assertThat(cleanWeek.detail()).contains("2 DENY");
    }
}
