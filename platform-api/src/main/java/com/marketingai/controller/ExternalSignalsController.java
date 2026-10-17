package com.marketingai.controller;

import com.marketingai.scheduler.ExternalSignalsScheduler;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * External Signals + Event Calendar API (3-12).
 * GET  /api/v1/signals/calendar
 * POST /api/v1/signals/calendar
 */
@RestController
@RequestMapping("/api/v1/signals")
@RequiredArgsConstructor
public class ExternalSignalsController {

    private final JdbcTemplate jdbc;
    private final ExternalSignalsScheduler scheduler;

    record CalendarEventRequest(
            String name, String tag,
            String starts_on, String ends_on,
            String geo, String expected_effect) {}

    @GetMapping("/calendar")
    public List<Map<String, Object>> getCalendar(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        UUID tenantId = currentTenant();

        LocalDate fromDate = from != null ? LocalDate.parse(from) : LocalDate.now().minusDays(30);
        LocalDate toDate   = to   != null ? LocalDate.parse(to)   : LocalDate.now().plusDays(90);

        return jdbc.queryForList("""
                SELECT id, tenant_id, name, tag, starts_on, ends_on, geo, expected_effect, created_at
                FROM event_calendar
                WHERE (tenant_id = ? OR tenant_id IS NULL)
                  AND starts_on <= ? AND ends_on >= ?
                ORDER BY starts_on
                """, tenantId, toDate, fromDate);
    }

    @PostMapping("/calendar")
    public Map<String, Object> createCalendarEvent(@RequestBody CalendarEventRequest body) {
        UUID tenantId = currentTenant();

        if (body.name() == null || body.tag() == null
                || body.starts_on() == null || body.ends_on() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name, tag, starts_on, ends_on are required");
        }

        LocalDate startsOn = LocalDate.parse(body.starts_on());
        LocalDate endsOn   = LocalDate.parse(body.ends_on());

        scheduler.saveCalendarEvent(tenantId, body.name(), body.tag(),
                startsOn, endsOn, body.geo(), body.expected_effect());

        return Map.of(
                "status", "created",
                "name", body.name(),
                "tag", body.tag(),
                "starts_on", startsOn.toString(),
                "ends_on", endsOn.toString()
        );
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
