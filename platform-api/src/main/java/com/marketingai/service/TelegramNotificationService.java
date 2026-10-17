package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.entity.BudgetGuard;
import com.marketingai.entity.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final AppProperties props;
    private final RestTemplate restTemplate;

    public void sendBudgetAlert(BudgetGuard guard, Tenant tenant) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) {
            log.warn("Telegram not configured — skipping alert for guard {}", guard.getId());
            return;
        }

        String text = buildBudgetAlertText(guard, tenant);
        sendMessage(cfg, text);
    }

    public void sendFraudAlert(UUID tenantId, long suspicious, long total, double rate) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) {
            log.warn("Telegram not configured — skipping fraud alert for tenant {}", tenantId);
            return;
        }
        String text = String.format(
                "⚠️ Fraud Rate Alert — Tenant: %s\n\n" +
                "Suspicious sessions (last 1h): %d / %d (%.1f%%)\n\n" +
                "Action Required: Review traffic sources in Meta Ads Manager. " +
                "Check for bot-like patterns or click farm activity.",
                tenantId, suspicious, total, rate * 100);
        sendMessage(cfg, text);
    }

    public void sendWeeklyReport(String reportText) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) return;
        sendMessage(cfg, reportText);
    }

    public void sendPixelPollutionAlert(UUID tenantId, String campaign,
                                        double rate14, double baseline, double driftPct) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) {
            log.warn("Telegram not configured — skipping pixel pollution alert for tenant {}", tenantId);
            return;
        }
        String text = String.format(
                "🔴 Pixel Pollution Drift — Tenant: %s\n\n" +
                "Campaign: %s\n" +
                "Quality Rate (14d): %.1f%%\n" +
                "Baseline (30d):     %.1f%%\n" +
                "Drift:              %.1f%%\n\n" +
                "Action Required: Review campaign targeting & creative. " +
                "Consider pausing the polluting campaign to protect the Meta pixel.",
                tenantId,
                campaign != null ? campaign : "(all campaigns)",
                rate14 * 100, baseline * 100, driftPct);
        sendMessage(cfg, text);
    }

    public void sendAlert(String text) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) {
            log.warn("Telegram not configured — skipping alert");
            return;
        }
        sendMessage(cfg, text);
    }

    public void sendCompetitiveIntelAlert(UUID tenantId, int adCount, String pageNames) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) return;
        String text = String.format(
                "🕵️ Competitive Intelligence Alert — Tenant: %s\n\n" +
                "Detected %d competitor ad(s) running >14 days from: %s\n\n" +
                "Counter-hypotheses generated. Login to approve the test batch.",
                tenantId, adCount, pageNames);
        sendMessage(cfg, text);
    }

    private String buildBudgetAlertText(BudgetGuard guard, Tenant tenant) {
        String type = "PER_CREATIVE_STOP_LOSS".equals(guard.getGuardType())
                ? "Per-Creative Stop-Loss"
                : "Monthly Ceiling";

        String ref = guard.getReferenceId() != null
                ? "Creative ID: " + guard.getReferenceId()
                : "Account-level";

        return String.format(
                "🚨 Budget Guard Triggered — %s\n\n" +
                "Type: %s\n" +
                "%s\n" +
                "Threshold: $%.2f\n" +
                "Current Spend: $%.2f\n\n" +
                "Action Required: Pause affected ad set(s) in Meta Ads Manager.",
                tenant.getName(),
                type,
                ref,
                guard.getThreshold(),
                guard.getCurrentSpend()
        );
    }

    private void sendMessage(AppProperties.Telegram cfg, String text) {
        try {
            String url = "https://api.telegram.org/bot" + cfg.getBotToken() + "/sendMessage";
            Map<String, Object> body = Map.of(
                    "chat_id", cfg.getChatId(),
                    "text", text,
                    "parse_mode", "HTML"
            );
            restTemplate.postForObject(url, body, String.class);
            log.info("Telegram alert sent to chat {}", cfg.getChatId());
        } catch (Exception e) {
            log.error("Failed to send Telegram alert: {}", e.getMessage());
        }
    }
}
