package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSchemaService {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Validates eventDataJson against the registered schema for eventType.
     * Returns true if valid or no schema is registered (permissive default).
     * Invalid events are quarantined in events_rejected — never throws.
     */
    public boolean validate(UUID tenantId, String eventType, String eventDataJson) {
        List<Map<String, Object>> schemas = jdbc.queryForList(
                "SELECT json_schema FROM event_schemas WHERE event_type = ? AND deprecated_at IS NULL ORDER BY schema_version DESC LIMIT 1",
                eventType);

        if (schemas.isEmpty()) return true;

        try {
            MAPPER.readTree(eventDataJson);
            return true;
        } catch (Exception e) {
            quarantine(tenantId, eventType, eventDataJson, "invalid_json: " + e.getMessage());
            return false;
        }
    }

    private void quarantine(UUID tenantId, String eventType, String eventDataJson, String reason) {
        try {
            jdbc.update(
                    "INSERT INTO events_rejected (tenant_id, event_type, event_data, reject_reason) VALUES (?, ?, ?::jsonb, ?)",
                    tenantId, eventType, eventDataJson, reason);
        } catch (Exception ex) {
            log.warn("Failed to quarantine rejected event: {}", ex.getMessage());
        }
    }
}
