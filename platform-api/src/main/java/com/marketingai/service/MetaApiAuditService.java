package com.marketingai.service;

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
public class MetaApiAuditService {

    private final JdbcTemplate jdbc;

    /**
     * Log a Meta API call. Pass {@code null} for errorMsg on success (M-19).
     */
    public void log(UUID tenantId, String actorType, UUID actorId,
                    String action, String targetType, String targetId,
                    String requestHash, int responseCode, String lane, long durationMs,
                    String errorMsg) {
        try {
            jdbc.update("""
                    INSERT INTO meta_api_audit
                        (tenant_id, actor_type, actor_id, action, target_type, target_id,
                         request_hash, response_code, queue_lane, duration_ms, error_msg)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, actorType, actorId, action, targetType, targetId,
                    requestHash, responseCode, lane, (int) durationMs, errorMsg);
        } catch (Exception e) {
            log.warn("MetaApiAuditService: failed to write audit entry: {}", e.getMessage());
        }
    }

    /** Backwards-compatible overload without errorMsg (defaults to null). */
    public void log(UUID tenantId, String actorType, UUID actorId,
                    String action, String targetType, String targetId,
                    String requestHash, int responseCode, String lane, long durationMs) {
        log(tenantId, actorType, actorId, action, targetType, targetId,
                requestHash, responseCode, lane, durationMs, null);
    }

    public List<Map<String, Object>> getAuditLog(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                SELECT id, actor_type, action, target_type, target_id, response_code,
                       queue_lane, duration_ms, error_msg, created_at
                FROM meta_api_audit
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, limit);
    }
}
