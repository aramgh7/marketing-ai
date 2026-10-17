package com.marketingai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecoveryDiagnosisServiceTest {

    private JdbcTemplate jdbc;
    private AutonomyService autonomyService;
    private RecoveryDiagnosisService service;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        autonomyService = mock(AutonomyService.class);
        service = new RecoveryDiagnosisService(jdbc, autonomyService);
    }

    @Test
    void diagnose_returns_map_with_diagnosis_key() {
        // CB event row
        Map<String, Object> cbRow = new HashMap<>();
        cbRow.put("id", eventId);
        cbRow.put("trigger_reason", "L2: CPM spike z-score=3.5");
        cbRow.put("level", "L2");

        when(jdbc.queryForMap(anyString(), eq(eventId), eq(tenantId))).thenReturn(cbRow);
        when(jdbc.queryForList(contains("site_health_incidents"), eq(tenantId))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("FROM sessions"), eq(tenantId)))
                .thenReturn(Map.of("total", 100L, "fraud_count", 5L));
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = service.diagnose(tenantId, eventId);

        assertThat(result).containsKey("diagnosis");
        assertThat(result).containsKey("resume_steps");
        assertThat(result).containsKey("confidence");
    }

    @Test
    void diagnose_returns_technical_when_incident_open() {
        Map<String, Object> cbRow = new HashMap<>();
        cbRow.put("trigger_reason", "CPM spike");
        cbRow.put("level", "L2");

        when(jdbc.queryForMap(anyString(), eq(eventId), eq(tenantId))).thenReturn(cbRow);
        // Site health incident is open
        when(jdbc.queryForList(contains("site_health_incidents"), eq(tenantId)))
                .thenReturn(List.of(Map.of("id", UUID.randomUUID())));
        when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);

        Map<String, Object> result = service.diagnose(tenantId, eventId);

        assertThat(result.get("diagnosis")).isEqualTo("TECHNICAL");
    }

    @Test
    void resume_batch_calls_autonomy_service() {
        // AUTOPILOT — autonomy check passes (no exception)
        doNothing().when(autonomyService).checkAndExecute(eq(tenantId), eq("CB_RESUME"), any());

        when(jdbc.update(anyString(), any(UUID.class), any(UUID.class))).thenReturn(1);

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        int count = service.resumeBatch(tenantId, List.of(id1, id2));

        verify(autonomyService).checkAndExecute(eq(tenantId), eq("CB_RESUME"), any());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void resume_batch_blocked_by_observer_mode() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "OBSERVER"))
                .when(autonomyService).checkAndExecute(eq(tenantId), eq("CB_RESUME"), any());

        assertThatThrownBy(() -> service.resumeBatch(tenantId, List.of(UUID.randomUUID())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void confirm_cause_updates_db() {
        when(jdbc.update(anyString(), eq("MARKET"), eq(eventId), eq(tenantId))).thenReturn(1);

        assertThatCode(() -> service.confirmCause(tenantId, eventId, "MARKET"))
                .doesNotThrowAnyException();

        verify(jdbc).update(anyString(), eq("MARKET"), eq(eventId), eq(tenantId));
    }
}
