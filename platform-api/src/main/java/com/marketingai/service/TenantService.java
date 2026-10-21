package com.marketingai.service;

import com.marketingai.dto.meta.MetaCredentialsRequest;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private final TenantRepository tenantRepo;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public void setMetaCredentials(UUID tenantId, MetaCredentialsRequest req) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        // Roadmap Phase 11: partial updates (page / instagram / ad account) without re-sending the token.
        if (req.hasToken()) {
            if (req.getAdAccountId() == null || req.getAdAccountId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "adAccountId is required when accessToken is sent");
            }
            tenant.setMetaAccessTokenEnc(vault.seal(req.getAccessToken()));
        }
        if (req.getAdAccountId() != null && !req.getAdAccountId().isBlank()) tenant.setMetaAdAccountId(req.getAdAccountId().trim());
        if (req.getAppId() != null) {
            tenant.setMetaAppId(req.getAppId());
        }
        if (req.getPageId() != null) tenant.setMetaPageId(req.getPageId().isBlank() ? null : req.getPageId().trim());
        if (req.getInstagramActorId() != null) {
            tenant.setMetaInstagramActorId(req.getInstagramActorId().isBlank() ? null : req.getInstagramActorId().trim());
        }
        tenantRepo.save(tenant);
        if (!req.hasToken()) {
            log.info("Meta credential fields updated for tenant {} (no token change)", tenantId);
            return;
        }
        // Token age starts now (TokenRotationScheduler counts from meta_token_created_at)
        jdbc.update("UPDATE tenants SET meta_token_created_at = now(), meta_token_status = 'OK' WHERE id = ?", tenantId);
        // Roadmap 9-2: a token that went INVALID dropped autonomy to OBSERVER — restore the previous level on re-auth
        jdbc.update("""
                UPDATE tenants
                SET autonomy_level = settings->>'autonomy_before_token_invalid',
                    settings = settings - 'autonomy_before_token_invalid'
                WHERE id = ? AND settings->>'autonomy_before_token_invalid' IS NOT NULL
                """, tenantId);
        log.info("Meta credentials updated for tenant {}", tenantId);
    }

    public Map<String, Object> getSettings(UUID tenantId) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report_email",      tenant.getReportEmail());
        result.put("alert_channel",     tenant.getAlertChannel() != null ? tenant.getAlertChannel() : "TELEGRAM");
        result.put("slack_webhook_url", tenant.getSlackWebhookUrl());
        // Roadmap Phase 11: FINAL (default) lets the final render be downloaded; PREVIEW_ONLY serves 403 for it
        result.put("creative_download_mode", com.marketingai.util.Jsonb.asMap(tenant.getSettings()).getOrDefault("creative_download_mode", "FINAL"));
        return result;
    }

    @Transactional
    public void updateSettings(UUID tenantId, Map<String, String> body) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        if (body.containsKey("report_email"))      tenant.setReportEmail(body.get("report_email"));
        if (body.containsKey("alert_channel"))     tenant.setAlertChannel(body.get("alert_channel"));
        if (body.containsKey("slack_webhook_url")) tenant.setSlackWebhookUrl(body.get("slack_webhook_url"));
        tenantRepo.save(tenant);
        if (body.containsKey("creative_download_mode")) {
            String mode = body.get("creative_download_mode") == null ? "FINAL" : body.get("creative_download_mode").toUpperCase();
            if (!mode.equals("FINAL") && !mode.equals("PREVIEW_ONLY")) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "creative_download_mode must be FINAL or PREVIEW_ONLY");
            }
            jdbc.update("UPDATE tenants SET settings = COALESCE(settings, '{}'::jsonb) || jsonb_build_object('creative_download_mode', ?::text) WHERE id = ?",
                    mode, tenantId);
        }
        log.info("Settings updated for tenant {}", tenantId);
    }

}
