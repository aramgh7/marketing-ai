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

/**
 * Consent analytics endpoint — admin dashboard (§12.1a).
 * Shows how many visitors accepted / refused tracking.
 *
 * Used by the marketer to understand:
 *  - What % of traffic is trackable (full consent)
 *  - Whether GDPR opt-outs are distorting model inputs
 *  - No-consent sessions are NEVER treated as bounces (vision §12.1a note)
 */
@RestController
@RequestMapping("/consent")
@RequiredArgsConstructor
public class ConsentController {

    private final JdbcTemplate jdbc;

    /** Consent level breakdown by time window for this tenant. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        UUID tid = currentTenant();
        return Map.of(
                "last_24h", consentBreakdown(tid, "24 hours"),
                "last_7d",  consentBreakdown(tid, "7 days")
        );
    }

    private Map<String, Object> consentBreakdown(UUID tenantId, String interval) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    COALESCE(consent_level, 'unknown')     AS consent_level,
                    COUNT(*)                               AS sessions
                FROM sessions
                WHERE tenant_id = ?
                  AND started_at > now() - CAST(? AS INTERVAL)
                GROUP BY consent_level
                ORDER BY sessions DESC
                """, tenantId, interval);

        long total = rows.stream()
                .mapToLong(r -> ((Number) r.get("sessions")).longValue())
                .sum();

        long fullConsent = rows.stream()
                .filter(r -> "full".equals(r.get("consent_level")))
                .mapToLong(r -> ((Number) r.get("sessions")).longValue())
                .sum();

        double consentRate = total > 0 ? Math.round(1000.0 * fullConsent / total) / 10.0 : 0.0;

        return Map.of(
                "total",          total,
                "full_consent",   fullConsent,
                "consent_rate_pct", consentRate,
                "breakdown",      rows
        );
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
