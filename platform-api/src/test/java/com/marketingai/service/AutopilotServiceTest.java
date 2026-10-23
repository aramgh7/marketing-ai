package com.marketingai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutopilotServiceTest {

    @Mock
    JdbcTemplate jdbc;

    @Mock
    MetaApiService metaApiService;  // M-9: required for real Meta calls

    @Mock
    TokenVault vault;               // M-9: required to decrypt tenant token

    @Mock
    MetaWriteGate metaWriteGate;    // M-9: all Meta writes must pass the gate

    @InjectMocks
    AutopilotService service;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {}

    // ─── evaluateActions ─────────────────────────────────────────────────────

    @Test
    void evaluateActions_disabledReturnsZero() {
        when(jdbc.queryForList(contains("autopilot_settings"), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of("enabled", false)));

        Map<String, Object> result = service.evaluateActions(TENANT_ID);

        assertThat(result.get("evaluated")).isEqualTo(0);
        assertThat(result.get("reason").toString()).contains("disabled");
    }

    @Test
    void evaluateActions_capReachedReturnsZero() {
        when(jdbc.queryForList(contains("autopilot_settings"), eq(TENANT_ID)))
                .thenReturn(List.of(Map.of(
                        "enabled", true,
                        "auto_pause_burnout", true,
                        "auto_scale_winner", false,
                        "auto_retire_graveyard", false,
                        "max_l1_triggers_per_day", 3,
                        "min_p_best_to_scale", 0.95,
                        "budget_scale_factor", 1.20
                )));
        when(jdbc.queryForObject(contains("COUNT"), eq(Integer.class), any()))
                .thenReturn(3);

        Map<String, Object> result = service.evaluateActions(TENANT_ID);

        assertThat(result.get("evaluated")).isEqualTo(0);
    }

    // ─── isSafeToExecute ─────────────────────────────────────────────────────

    @Test
    void isSafeToExecute_pauseBurnoutRequiresFlag() {
        Map<String, Object> candidate = Map.of("action_type", "PAUSE_BURNOUT", "target_id", "ad1");
        Map<String, Object> settingsOff = Map.of("auto_pause_burnout", false, "min_p_best_to_scale", 0.95);
        Map<String, Object> settingsOn  = Map.of("auto_pause_burnout", true,  "min_p_best_to_scale", 0.95);

        assertThat(service.isSafeToExecute(candidate, settingsOff)).isFalse();
        assertThat(service.isSafeToExecute(candidate, settingsOn)).isTrue();
    }

    @Test
    void isSafeToExecute_scaleWinnerRequiresPBestThreshold() {
        Map<String, Object> candidate = Map.of(
                "action_type", "SCALE_WINNER",
                "p_best", 0.94
        );
        Map<String, Object> settings = Map.of(
                "auto_scale_winner", true,
                "min_p_best_to_scale", 0.95
        );
        assertThat(service.isSafeToExecute(candidate, settings)).isFalse();
    }

    @Test
    void isSafeToExecute_scaleWinnerAboveThreshold() {
        Map<String, Object> candidate = Map.of(
                "action_type", "SCALE_WINNER",
                "p_best", 0.97
        );
        Map<String, Object> settings = Map.of(
                "auto_scale_winner", true,
                "min_p_best_to_scale", 0.95
        );
        assertThat(service.isSafeToExecute(candidate, settings)).isTrue();
    }
}
