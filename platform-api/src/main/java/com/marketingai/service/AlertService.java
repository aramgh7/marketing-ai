package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Unified alert service: Telegram and/or Slack behind one interface.
 * Channel selection via app.alert-channel: TELEGRAM | SLACK | BOTH (default).
 * Per-tenant Slack webhook overrides the global one when provided.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AppProperties props;
    private final RestTemplate restTemplate;

    public void sendAlert(String message) {
        sendAlert(message, null);
    }

    public void sendAlert(String message, String tenantSlackWebhook) {
        String channel = props.getAlertChannel() != null ? props.getAlertChannel() : "BOTH";
        if ("TELEGRAM".equals(channel) || "BOTH".equals(channel)) {
            sendTelegram(message);
        }
        if ("SLACK".equals(channel) || "BOTH".equals(channel)) {
            String webhookUrl = (tenantSlackWebhook != null && !tenantSlackWebhook.isBlank())
                    ? tenantSlackWebhook
                    : props.getSlackWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                sendSlack(message, webhookUrl);
            }
        }
    }

    private void sendTelegram(String message) {
        AppProperties.Telegram cfg = props.getTelegram();
        if (!cfg.isConfigured()) {
            log.warn("Telegram not configured — skipping alert");
            return;
        }
        try {
            String url = "https://api.telegram.org/bot" + cfg.getBotToken() + "/sendMessage";
            restTemplate.postForObject(url,
                    Map.of("chat_id", cfg.getChatId(), "text", message, "parse_mode", "HTML"),
                    String.class);
            log.debug("Telegram alert sent");
        } catch (Exception e) {
            log.error("Telegram alert failed: {}", e.getMessage());
        }
    }

    private void sendSlack(String message, String webhookUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForObject(webhookUrl,
                    new HttpEntity<>(Map.of("text", message), headers),
                    String.class);
            log.debug("Slack alert sent");
        } catch (Exception e) {
            log.error("Slack alert failed: {}", e.getMessage());
        }
    }
}
