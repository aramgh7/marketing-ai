package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily Meta token age check.
 * Day 50+: attempt rotation; day 58+: mark INVALID and alert.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRotationScheduler {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;
    private final AppProperties appProperties;

    @Scheduled(cron = "0 0 4 * * *")
    public void rotateTokens() {
        log.info("TokenRotationScheduler: checking Meta token ages");

        List<Map<String, Object>> tenants = jdbc.queryForList("""
                SELECT id, name, meta_token_status, meta_token_created_at
                FROM tenants
                WHERE meta_access_token_enc IS NOT NULL
                """);

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            String name   = (String) tenant.get("name");

            Timestamp createdAt = (Timestamp) tenant.get("meta_token_created_at");
            if (createdAt == null) continue;

            long daysOld = ChronoUnit.DAYS.between(
                    createdAt.toLocalDateTime().toLocalDate(), LocalDate.now());

            try {
                if (daysOld >= 58) {
                    jdbc.update("UPDATE tenants SET meta_token_status = 'INVALID' WHERE id = ?", tenantId);
                    alertService.sendAlert(
                            "🔴 Meta token INVALID for tenant " + name +
                            " (age: " + daysOld + " days). Platform paused Meta writes.");
                    log.warn("Tenant {} Meta token INVALID ({} days old)", name, daysOld);

                } else if (daysOld >= 50) {
                    boolean rotated = attemptTokenRotation(tenantId);
                    if (rotated) {
                        jdbc.update(
                                "UPDATE tenants SET meta_token_status = 'OK', meta_token_rotated_at = now() WHERE id = ?",
                                tenantId);
                        log.info("Tenant {} Meta token rotated successfully", name);
                    } else {
                        jdbc.update("UPDATE tenants SET meta_token_status = 'EXPIRING' WHERE id = ?", tenantId);
                        alertService.sendAlert(
                                "⚠️ Meta token EXPIRING for tenant " + name +
                                " (age: " + daysOld + " days). Manual rotation required.");
                    }
                }
            } catch (Exception e) {
                log.error("TokenRotationScheduler error for tenant {}: {}", name, e.getMessage());
            }
        }
    }

    private boolean attemptTokenRotation(UUID tenantId) {
        // Full token rotation requires META_APP_ID + META_APP_SECRET.
        // Graph API: GET /v20.0/oauth/access_token?grant_type=fb_exchange_token&...
        // Returns false (EXPIRING alert) until credentials are configured.
        String appId     = appProperties.getMeta() != null ? appProperties.getMeta().getAppId()     : null;
        String appSecret = appProperties.getMeta() != null ? appProperties.getMeta().getAppSecret() : null;
        if (appId == null || appId.isBlank() || appSecret == null || appSecret.isBlank()) {
            log.warn("Meta app credentials not configured — token rotation skipped for tenant {}", tenantId);
            return false;
        }
        return false; // placeholder — wire real exchange when credentials are present
    }
}
