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

    @Transactional
    public void setMetaCredentials(UUID tenantId, MetaCredentialsRequest req) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        tenant.setMetaAccessTokenEnc(req.getAccessToken().getBytes(StandardCharsets.UTF_8));
        tenant.setMetaAdAccountId(req.getAdAccountId());
        if (req.getAppId() != null) {
            tenant.setMetaAppId(req.getAppId());
        }
        tenantRepo.save(tenant);
        log.info("Meta credentials updated for tenant {}", tenantId);
    }

    public Map<String, Object> getSettings(UUID tenantId) {
        Tenant tenant = tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("report_email",      tenant.getReportEmail());
        result.put("alert_channel",     tenant.getAlertChannel() != null ? tenant.getAlertChannel() : "TELEGRAM");
        result.put("slack_webhook_url", tenant.getSlackWebhookUrl());
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
        log.info("Settings updated for tenant {}", tenantId);
    }

}
