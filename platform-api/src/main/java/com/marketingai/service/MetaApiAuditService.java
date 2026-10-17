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

    public void log(UUID tenantId, String actorType, UUID actorId,
                    String action, String targetType, String targetId,
                    String requestHash, int responseCode, String lane, long durationMs) {
        try {
            jdbc.update("""
                    INSERT INTO meta_api_audit
                        (tenant_id, actor_type, actor_id, action, target_type, target_id,
                         request_hash, response_code, queue_lane, duration_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    tenantId, actorType, actorId, action, targetType, targetId,
                    requestHash, responseCode, lane, (int) durationMs);
        } catch (Exception e) {
            log.warn("MetaApiAuditService: failed to write audit entry: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getAuditLog(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                SELECT id, actor_type, action, target_type, target_id, response_code,
                       queue_lane, duration_ms, created_at
                FROM meta_api_audit
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, limit);
    }
}
