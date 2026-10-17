package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/fraud")
@RequiredArgsConstructor
public class FraudController {

    private final JdbcTemplate jdbc;

    /**
     * Fraud summary for the current tenant — used in admin dashboard.
     * Returns counts for last 1h and 24h windows, plus top fraud reasons.
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        UUID tid = currentTenant();

        Map<String, Object> last1h = jdbc.queryForMap("""
                SELECT
                    COUNT(*)                                         AS total,
                    SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END)  AS suspicious,
                    ROUND(
                        CASE WHEN COUNT(*) = 0 THEN 0
                        ELSE 100.0 * SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END) / COUNT(*)
                        END, 1)                                      AS fraud_rate_pct
                FROM sessions
                WHERE tenant_id = ? AND started_at > now() - interval '1 hour'
                """, tid);

        Map<String, Object> last24h = jdbc.queryForMap("""
                SELECT
                    COUNT(*)                                         AS total,
                    SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END)  AS suspicious,
                    ROUND(
                        CASE WHEN COUNT(*) = 0 THEN 0
                        ELSE 100.0 * SUM(CASE WHEN is_suspicious THEN 1 ELSE 0 END) / COUNT(*)
                        END, 1)                                      AS fraud_rate_pct
                FROM sessions
                WHERE tenant_id = ? AND started_at > now() - interval '24 hours'
                """, tid);

        // Top fraud reasons in last 24h (unnest JSONB array)
        List<Map<String, Object>> topReasons = jdbc.queryForList("""
                SELECT reason, COUNT(*) AS cnt
                FROM (
                    SELECT jsonb_array_elements_text(fraud_reasons) AS reason
                    FROM sessions
                    WHERE tenant_id = ?
                      AND is_suspicious = true
                      AND started_at > now() - interval '24 hours'
                ) r
                GROUP BY reason
                ORDER BY cnt DESC
                LIMIT 10
                """, tid);

        return Map.of(
                "last_1h",    last1h,
                "last_24h",   last24h,
                "top_reasons", topReasons
        );
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
