package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;

/**
 * Single choke point for all Meta write operations (3-17).
 * Checks tenants.autonomy_level before executing writes.
 *
 * Levels: OBSERVER < ADVISOR < SEMI_AUTOPILOT < AUTOPILOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutonomyService {

    private static final Set<String> VALID_LEVELS = Set.of(
            "OBSERVER", "ADVISOR", "SEMI_AUTOPILOT", "AUTOPILOT"
    );

    // Actions that require AUTOPILOT even in SEMI_AUTOPILOT mode
    private static final Set<String> AUTOPILOT_ONLY_ACTIONS = Set.of(
            "CAMPAIGN_CREATE", "AUDIENCE_UPDATE"
    );

    private final JdbcTemplate jdbc;

    /**
     * Check autonomy level and execute the write if allowed.
     * Throws 403 ResponseStatusException if blocked.
     */
    public void checkAndExecute(UUID tenantId, String action, Runnable write) {
        String level = getAutonomyLevel(tenantId);
        switch (level) {
            case "OBSERVER" ->
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "OBSERVER mode — no writes allowed. Switch to ADVISOR or higher.");
            case "ADVISOR" ->
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "ADVISOR mode — human approval required before writes.");
            case "SEMI_AUTOPILOT" -> {
                if (AUTOPILOT_ONLY_ACTIONS.contains(action)) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "Action '" + action + "' requires AUTOPILOT level.");
                }
                log.info("AutonomyService SEMI_AUTOPILOT executing: tenant={} action={}", tenantId, action);
                write.run();
            }
            case "AUTOPILOT" -> {
                log.info("AutonomyService AUTOPILOT executing: tenant={} action={}", tenantId, action);
                write.run();
            }
            default ->
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Unknown autonomy level: " + level);
        }
    }

    public String getAutonomyLevel(UUID tenantId) {
        try {
            String level = jdbc.queryForObject(
                    "SELECT autonomy_level FROM tenants WHERE id = ?",
                    String.class, tenantId);
            return level != null ? level : "ADVISOR";
        } catch (Exception e) {
            log.warn("Could not fetch autonomy_level for tenant {}: {}", tenantId, e.getMessage());
            return "ADVISOR";
        }
    }

    public void setAutonomyLevel(UUID tenantId, String level) {
        if (!VALID_LEVELS.contains(level)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid autonomy level '" + level + "'. Valid: " + VALID_LEVELS);
        }
        jdbc.update("UPDATE tenants SET autonomy_level = ? WHERE id = ?", level, tenantId);
        log.info("AutonomyService: tenant={} level set to {}", tenantId, level);
    }
}
