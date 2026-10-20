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
 * Persona Sandbox CRUD (3-22).
 * GET    /api/v1/personas
 * POST   /api/v1/personas
 * DELETE /api/v1/personas/{id}
 */
@RestController
@RequestMapping("/personas")
@RequiredArgsConstructor
public class PersonaController {

    private final JdbcTemplate jdbc;

    record CreatePersonaRequest(
            String name, String description,
            Integer cluster_id, Object trait_snapshot) {}

    @GetMapping
    public List<Map<String, Object>> listPersonas() {
        UUID tenantId = currentTenant();
        return jdbc.queryForList("""
                SELECT id, name, description, traits, created_at
                FROM personas
                WHERE tenant_id = ? AND deleted_at IS NULL
                ORDER BY created_at DESC
                """, tenantId);
    }

    @PostMapping
    public Map<String, Object> createPersona(@RequestBody CreatePersonaRequest body) {
        UUID tenantId = currentTenant();

        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }

        UUID id = UUID.randomUUID();
        // Store cluster_id inside traits JSONB since personas table has a flexible traits column
        String traitsJson = body.cluster_id() != null
                ? "{\"cluster_id\": " + body.cluster_id() + "}"
                : "{}";
        jdbc.update("""
                INSERT INTO personas (id, tenant_id, name, description, traits, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                """, id, tenantId, body.name(), body.description(), traitsJson);

        return Map.of(
                "id", id,
                "name", body.name(),
                "description", body.description() != null ? body.description() : "",
                "cluster_id", body.cluster_id() != null ? body.cluster_id() : 0
        );
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deletePersona(@PathVariable UUID id) {
        UUID tenantId = currentTenant();
        int updated = jdbc.update("""
                UPDATE personas SET deleted_at = now()
                WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                """, id, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona not found");
        }
        return Map.of("status", "deleted", "id", id.toString());
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
