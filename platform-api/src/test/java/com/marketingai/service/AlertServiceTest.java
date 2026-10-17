package com.marketingai.service;

import com.marketingai.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlertServiceTest {

    private AppProperties props;
    private RestTemplate restTemplate;
    private AlertService alertService;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        restTemplate = mock(RestTemplate.class);
        alertService = new AlertService(props, restTemplate);
    }

    @Test
    void sendAlert_telegramConfigured_callsTelegram() {
        props.getTelegram().setBotToken("testtoken");
        props.getTelegram().setChatId("123456");
        props.setAlertChannel("TELEGRAM");

        alertService.sendAlert("Test message");

        verify(restTemplate, times(1)).postForObject(
                contains("api.telegram.org"), any(), eq(String.class));
    }

    @Test
    void sendAlert_slackConfigured_callsSlack() {
        props.setSlackWebhookUrl("https://hooks.slack.com/test");
        props.setAlertChannel("SLACK");

        alertService.sendAlert("Test Slack message");

        verify(restTemplate, times(1)).postForObject(
                eq("https://hooks.slack.com/test"), any(), eq(String.class));
    }

    @Test
    void sendAlert_both_callsBothChannels() {
        props.getTelegram().setBotToken("tok");
        props.getTelegram().setChatId("cid");
        props.setSlackWebhookUrl("https://hooks.slack.com/test");
        props.setAlertChannel("BOTH");

        alertService.sendAlert("Both channels");

        verify(restTemplate, times(2)).postForObject(anyString(), any(), eq(String.class));
    }

    @Test
    void sendAlert_tenantWebhookOverridesGlobal() {
        props.setSlackWebhookUrl("https://hooks.slack.com/global");
        props.setAlertChannel("SLACK");

        alertService.sendAlert("Tenant message", "https://hooks.slack.com/tenant");

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate, times(1)).postForObject(urlCaptor.capture(), any(), eq(String.class));
        assertThat(urlCaptor.getValue()).isEqualTo("https://hooks.slack.com/tenant");
    }

    @Test
    void sendAlert_noChannelsConfigured_noRestCalls() {
        props.setAlertChannel("BOTH");
        // Neither Telegram nor Slack configured

        alertService.sendAlert("Should not send");

        verify(restTemplate, never()).postForObject(anyString(), any(), eq(String.class));
    }
}
