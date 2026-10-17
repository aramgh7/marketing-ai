package com.marketingai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutonomyServiceTest {

    private JdbcTemplate jdbc;
    private AutonomyService autonomyService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        autonomyService = new AutonomyService(jdbc);
    }

    @Test
    void observer_blocks_all_writes() {
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(tenantId)))
                .thenReturn("OBSERVER");

        assertThatThrownBy(() -> autonomyService.checkAndExecute(tenantId, "BID_UPDATE", () -> {}))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                });
    }

    @Test
    void advisor_blocks_all_writes() {
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(tenantId)))
                .thenReturn("ADVISOR");

        assertThatThrownBy(() -> autonomyService.checkAndExecute(tenantId, "BUDGET_UPDATE", () -> {}))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                });
    }

    @Test
    void semi_autopilot_blocks_campaign_create() {
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(tenantId)))
                .thenReturn("SEMI_AUTOPILOT");

        assertThatThrownBy(() -> autonomyService.checkAndExecute(tenantId, "CAMPAIGN_CREATE", () -> {}))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode() == HttpStatus.FORBIDDEN;
                });
    }

    @Test
    void semi_autopilot_allows_bid_update() {
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(tenantId)))
                .thenReturn("SEMI_AUTOPILOT");

        Runnable write = mock(Runnable.class);
        assertThatCode(() -> autonomyService.checkAndExecute(tenantId, "BID_UPDATE", write))
                .doesNotThrowAnyException();
        verify(write).run();
    }

    @Test
    void autopilot_allows_all_actions() {
        when(jdbc.queryForObject(any(String.class), eq(String.class), eq(tenantId)))
                .thenReturn("AUTOPILOT");

        Runnable write = mock(Runnable.class);
        assertThatCode(() -> autonomyService.checkAndExecute(tenantId, "CAMPAIGN_CREATE", write))
                .doesNotThrowAnyException();
        verify(write).run();
    }

    @Test
    void set_invalid_level_throws_bad_request() {
        assertThatThrownBy(() -> autonomyService.setAutonomyLevel(tenantId, "GOD_MODE"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assert rse.getStatusCode() == HttpStatus.BAD_REQUEST;
                });
    }
}
