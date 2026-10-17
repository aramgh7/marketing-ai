package com.marketingai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventSchemaServiceTest {

    private JdbcTemplate jdbc;
    private EventSchemaService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new EventSchemaService(jdbc);
    }

    @Test
    void validate_noSchemaRegistered_permissive() {
        when(jdbc.queryForList(anyString(), eq("PAGE_VIEW"))).thenReturn(List.of());
        assertThat(service.validate(UUID.randomUUID(), "PAGE_VIEW", "{}")).isTrue();
    }

    @Test
    void validate_validJson_withSchema_passes() {
        when(jdbc.queryForList(anyString(), eq("PAGE_VIEW")))
                .thenReturn(List.of(Map.of("json_schema", "{\"type\":\"object\"}")));
        assertThat(service.validate(UUID.randomUUID(), "PAGE_VIEW", "{\"url\":\"https://example.com\"}")).isTrue();
    }

    @Test
    void validate_invalidJson_quarantines_returnsFalse() {
        when(jdbc.queryForList(anyString(), eq("PAGE_VIEW")))
                .thenReturn(List.of(Map.of("json_schema", "{\"type\":\"object\"}")));
        assertThat(service.validate(UUID.randomUUID(), "PAGE_VIEW", "not-json")).isFalse();
    }

    @Test
    void validate_nullEventData_fails() {
        when(jdbc.queryForList(anyString(), eq("CLICK")))
                .thenReturn(List.of(Map.of("json_schema", "{\"type\":\"object\"}")));
        assertThat(service.validate(UUID.randomUUID(), "CLICK", null)).isFalse();
    }

    @Test
    void validate_unknownEventType_permissive() {
        when(jdbc.queryForList(anyString(), eq("UNKNOWN_TYPE"))).thenReturn(List.of());
        assertThat(service.validate(UUID.randomUUID(), "UNKNOWN_TYPE", "{\"x\":1}")).isTrue();
    }
}
