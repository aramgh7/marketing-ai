package com.marketingai.scheduler;

import com.marketingai.config.AppProperties;
import com.marketingai.service.AlertService;
import com.marketingai.service.MetaApiAuditService;
import com.marketingai.service.MetaTokenExchange;
import com.marketingai.service.TokenVault;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily Meta token age check (roadmap 9-2; arch §15.15).
 *   day 50+: exchange the long-lived token (fb_exchange_token) and store it sealed;
 *   day 58+: INVALID → Meta writes paused, autonomy → OBSERVER (previous level kept in
 *            tenants.settings.autonomy_before_token_invalid and restored by TenantService on re-auth).
 * Every exchange call is written to meta_api_audit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRotationScheduler {

    private final JdbcTemplate jdbc;
    private final AlertService alertService;
    private final AppProperties appProperties;
    private final TokenVault vault;
    private final RestTemplate restTemplate;
    private final MetaApiAuditService auditService;

    @Scheduled(cron = "0 0 4 * * *", zone = "UTC")
    public void rotateTokens() {
        log.info("TokenRotationScheduler: checking Meta token ages");

        List<Map<String, Object>> tenants = jdbc.queryForList("""
                SELECT id, name, meta_token_status, meta_token_created_at, meta_access_token_enc, autonomy_level
                FROM tenants
                WHERE meta_access_token_enc IS NOT NULL
                """);

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            String name   = (String) tenant.get("name");
            Timestamp createdAt = (Timestamp) tenant.get("meta_token_created_at");
            if (createdAt == null) continue;

            long daysOld = ChronoUnit.DAYS.between(createdAt.toLocalDateTime().toLocalDate(), LocalDate.now());
            try {
                switch (MetaTokenExchange.decide(daysOld)) {
                    case INVALID -> markInvalid(tenantId, name, daysOld, (String) tenant.get("autonomy_level"));
                    case ROTATE -> {
                        boolean rotated = attemptTokenRotation(tenantId, (byte[]) tenant.get("meta_access_token_enc"));
                        if (rotated) {
                            log.info("Tenant {} Meta token rotated successfully", name);
                        } else {
                            jdbc.update("UPDATE tenants SET meta_token_status = 'EXPIRING' WHERE id = ?", tenantId);
                            alertService.sendAlert("⚠️ Meta token EXPIRING for tenant " + name +
                                    " (age: " + daysOld + " days). Automatic rotation failed — manual re-auth required.");
                        }
                    }
                    case OK -> { /* nothing */ }
                }
            } catch (Exception e) {
                log.error("TokenRotationScheduler error for tenant {}: {}", name, e.getMessage());
            }
        }
    }

    /** INVALID: pause Meta writes and drop autonomy to OBSERVER (idempotent). */
    public void markInvalid(UUID tenantId, String name, long daysOld, String currentLevel) {
        jdbc.update("UPDATE tenants SET meta_token_status = 'INVALID' WHERE id = ?", tenantId);
        if (currentLevel != null && !"OBSERVER".equals(currentLevel)) {
            jdbc.update("""
                    UPDATE tenants
                    SET settings = COALESCE(settings, '{}'::jsonb)
                                   || jsonb_build_object('autonomy_before_token_invalid', ?::text),
                        autonomy_level = 'OBSERVER'
                    WHERE id = ?
                    """, currentLevel, tenantId);
            log.warn("Tenant {} autonomy {} → OBSERVER (Meta token INVALID)", name, currentLevel);
        }
        alertService.sendAlert("🔴 Meta token INVALID for tenant " + name + " (age: " + daysOld +
                " days). Meta writes paused, autonomy set to OBSERVER until the token is re-authorised.");
        log.warn("Tenant {} Meta token INVALID ({} days old)", name, daysOld);
    }

    /** Exchange the current long-lived token; true when a new token was stored. */
    public boolean attemptTokenRotation(UUID tenantId, byte[] sealedToken) {
        String appId     = appProperties.getMeta() != null ? appProperties.getMeta().getAppId()     : null;
        String appSecret = appProperties.getMeta() != null ? appProperties.getMeta().getAppSecret() : null;
        if (!MetaTokenExchange.credentialsPresent(appId, appSecret)) {
            log.warn("Meta app credentials not configured — token rotation skipped for tenant {}", tenantId);
            return false;
        }
        String current = vault.openOrNull(sealedToken);
        if (current == null) return false;

        long started = System.currentTimeMillis();
        int code = 0;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(
                    MetaTokenExchange.exchangeUrl(appId, appSecret, current), Map.class);
            String fresh = MetaTokenExchange.parseNewToken(resp);
            code = fresh != null ? 200 : 502;
            if (fresh == null) {
                log.warn("Meta token exchange returned no access_token for tenant {}", tenantId);
                return false;
            }
            jdbc.update("""
                    UPDATE tenants
                    SET meta_access_token_enc = ?, meta_token_status = 'OK',
                        meta_token_created_at = now(), meta_token_rotated_at = now()
                    WHERE id = ?
                    """, vault.seal(fresh), tenantId);
            return true;
        } catch (Exception e) {
            code = 500;
            log.warn("Meta token exchange failed for tenant {}: {}", tenantId, e.getMessage());
            return false;
        } finally {
            auditService.log(tenantId, "JOB", null, "TOKEN_EXCHANGE", "tenant", tenantId.toString(),
                    MetaTokenExchange.mask(current), code, "P1", System.currentTimeMillis() - started);
        }
    }
}
