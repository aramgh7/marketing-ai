package com.marketingai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GDPR Right to Erasure — POST /api/v1/privacy/erasure
 * Deletes all data tied to a subject (by crm_user_id or external_id_hash).
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/privacy")
@RequiredArgsConstructor
public class ErasureController {

    private final JdbcTemplate jdbc;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostMapping("/erasure")
    public ResponseEntity<Map<String, Object>> requestErasure(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, String> body) {

        String subjectKeyType = body.get("subject_key_type");
        String subjectKey     = body.get("subject_key");

        if (subjectKey == null || subjectKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "subject_key is required");
        }
        if (!"CRM_USER_ID".equals(subjectKeyType) && !"EXTERNAL_ID_HASH".equals(subjectKeyType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "subject_key_type must be CRM_USER_ID or EXTERNAL_ID_HASH");
        }

        String idColumn = "CRM_USER_ID".equals(subjectKeyType) ? "crm_user_id" : "external_id_hash";

        List<UUID> sessionIds = jdbc.queryForList(
                "SELECT id FROM sessions WHERE tenant_id = ? AND " + idColumn + " = ?",
                UUID.class, tenantId, subjectKey);

        int deletedEvents = 0, deletedDecisions = 0, deletedSessions = 0;

        if (!sessionIds.isEmpty()) {
            String placeholders = sessionIds.stream().map(id -> "?").collect(Collectors.joining(","));

            Object[] eventsArgs = new Object[1 + sessionIds.size()];
            eventsArgs[0] = tenantId;
            for (int i = 0; i < sessionIds.size(); i++) eventsArgs[i + 1] = sessionIds.get(i);

            deletedEvents = jdbc.update(
                    "DELETE FROM events WHERE tenant_id = ? AND session_id IN (" + placeholders + ")",
                    eventsArgs);

            deletedDecisions = jdbc.update(
                    "DELETE FROM decision_log WHERE tenant_id = ? AND session_id IN (" + placeholders + ")",
                    eventsArgs);

            jdbc.update(
                    "UPDATE ghl_webhook_log SET payload = '{\"redacted\":true}'::jsonb WHERE tenant_id = ? AND ghl_contact_id = ?",
                    tenantId, subjectKey);

            deletedSessions = jdbc.update(
                    "DELETE FROM sessions WHERE tenant_id = ? AND " + idColumn + " = ?",
                    tenantId, subjectKey);
        }

        Map<String, Integer> rowsDeleted = Map.of(
                "sessions", deletedSessions,
                "events", deletedEvents,
                "decision_log", deletedDecisions);

        String keyHash = subjectKey.length() > 8 ? subjectKey.substring(0, 8) + "..." : subjectKey;
        try {
            jdbc.update("""
                    INSERT INTO erasure_requests (tenant_id, subject_key_type, subject_key_hash, completed_at, rows_deleted)
                    VALUES (?, ?, ?, now(), ?::jsonb)
                    """,
                    tenantId, subjectKeyType, keyHash, MAPPER.writeValueAsString(rowsDeleted));
        } catch (Exception e) {
            log.warn("Failed to write erasure audit record: {}", e.getMessage());
        }

        log.info("Erasure completed: tenant={} type={} sessions={} events={} decisions={}",
                tenantId, subjectKeyType, deletedSessions, deletedEvents, deletedDecisions);

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "rows_deleted", rowsDeleted,
                "sessions_found", sessionIds.size()));
    }
}
