package com.marketingai.service;

import com.marketingai.config.AppProperties;
import com.marketingai.scheduler.TokenRotationScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Roadmap 9-2: exchange URL, response parsing, age decisions, INVALID → OBSERVER, real exchange path. */
class MetaTokenExchangeTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void decision_thresholds() {
        assertThat(MetaTokenExchange.decide(0)).isEqualTo(MetaTokenExchange.Decision.OK);
        assertThat(MetaTokenExchange.decide(49)).isEqualTo(MetaTokenExchange.Decision.OK);
        assertThat(MetaTokenExchange.decide(50)).isEqualTo(MetaTokenExchange.Decision.ROTATE);
        assertThat(MetaTokenExchange.decide(57)).isEqualTo(MetaTokenExchange.Decision.ROTATE);
        assertThat(MetaTokenExchange.decide(58)).isEqualTo(MetaTokenExchange.Decision.INVALID);
    }

    @Test
    void exchange_url_carries_grant_and_encoded_params() {
        String url = MetaTokenExchange.exchangeUrl("123", "s3cr&t", "EAAB token");
        assertThat(url).startsWith("https://graph.facebook.com/v20.0/oauth/access_token?grant_type=fb_exchange_token")
                .contains("client_id=123").contains("client_secret=s3cr%26t").contains("fb_exchange_token=EAAB+token");
    }

    @Test
    void parse_and_mask() {
        assertThat(MetaTokenExchange.parseNewToken(Map.of("access_token", "EAABnew", "expires_in", 5184000))).isEqualTo("EAABnew");
        assertThat(MetaTokenExchange.parseNewToken(Map.of("error", "x"))).isNull();
        assertThat(MetaTokenExchange.parseNewToken(null)).isNull();
        assertThat(MetaTokenExchange.mask("EAABxxxxxxxxxxxxxxxx1234")).isEqualTo("EAABxx…1234");
        assertThat(MetaTokenExchange.mask("short")).isEqualTo("***");
        assertThat(MetaTokenExchange.credentialsPresent("a", "b")).isTrue();
        assertThat(MetaTokenExchange.credentialsPresent("a", "")).isFalse();
    }

    @Test
    void markInvalid_sets_observer_and_keeps_previous_level() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AlertService alerts = mock(AlertService.class);
        TokenRotationScheduler s = new TokenRotationScheduler(jdbc, alerts, new AppProperties(),
                new TokenVault(new EncryptionService(KEY, false)), mock(RestTemplate.class), mock(MetaApiAuditService.class));
        UUID t = UUID.randomUUID();

        s.markInvalid(t, "Acme", 60, "SEMI_AUTOPILOT");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeast(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anySatisfy(q -> assertThat(q).contains("meta_token_status = 'INVALID'"));
        assertThat(sql.getAllValues()).anySatisfy(q -> assertThat(q).contains("autonomy_before_token_invalid").contains("autonomy_level = 'OBSERVER'"));
        verify(alerts).sendAlert(contains("INVALID"));
    }

    @Test
    void markInvalid_does_not_touch_autonomy_when_already_observer() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TokenRotationScheduler s = new TokenRotationScheduler(jdbc, mock(AlertService.class), new AppProperties(),
                new TokenVault(new EncryptionService(KEY, false)), mock(RestTemplate.class), mock(MetaApiAuditService.class));
        s.markInvalid(UUID.randomUUID(), "Acme", 60, "OBSERVER");
        verify(jdbc, times(1)).update(anyString(), any(Object[].class));
    }

    @Test
    void rotation_without_app_credentials_is_skipped() {
        TokenRotationScheduler s = new TokenRotationScheduler(mock(JdbcTemplate.class), mock(AlertService.class),
                new AppProperties(), new TokenVault(new EncryptionService(KEY, false)), mock(RestTemplate.class),
                mock(MetaApiAuditService.class));
        assertThat(s.attemptTokenRotation(UUID.randomUUID(), "tok".getBytes())).isFalse();
    }

    @Test
    void rotation_exchanges_and_stores_sealed_token() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RestTemplate rest = mock(RestTemplate.class);
        MetaApiAuditService audit = mock(MetaApiAuditService.class);
        AppProperties props = new AppProperties();
        props.getMeta().setAppId("app"); props.getMeta().setAppSecret("secret");
        TokenVault vault = new TokenVault(new EncryptionService(KEY, false));
        when(rest.getForObject(anyString(), eq(Map.class))).thenReturn(Map.of("access_token", "EAABfresh"));
        TokenRotationScheduler s = new TokenRotationScheduler(jdbc, mock(AlertService.class), props, vault, rest, audit);
        UUID t = UUID.randomUUID();

        boolean ok = s.attemptTokenRotation(t, vault.seal("EAABold"));

        assertThat(ok).isTrue();
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("meta_token_created_at = now()"), args.capture());
        assertThat(vault.open((byte[]) args.getValue()[0])).isEqualTo("EAABfresh");
        verify(audit).log(eq(t), eq("JOB"), isNull(), eq("TOKEN_EXCHANGE"), eq("tenant"), eq(t.toString()),
                anyString(), eq(200), eq("P1"), anyLong());
    }

    @Test
    void rotation_failure_returns_false_and_audits() {
        RestTemplate rest = mock(RestTemplate.class);
        MetaApiAuditService audit = mock(MetaApiAuditService.class);
        AppProperties props = new AppProperties();
        props.getMeta().setAppId("app"); props.getMeta().setAppSecret("secret");
        TokenVault vault = new TokenVault(new EncryptionService(KEY, false));
        when(rest.getForObject(anyString(), eq(Map.class))).thenThrow(new RuntimeException("boom"));
        TokenRotationScheduler s = new TokenRotationScheduler(mock(JdbcTemplate.class), mock(AlertService.class), props, vault, rest, audit);

        assertThat(s.attemptTokenRotation(UUID.randomUUID(), vault.seal("EAABold"))).isFalse();
        verify(audit).log(any(), eq("JOB"), isNull(), eq("TOKEN_EXCHANGE"), eq("tenant"), anyString(),
                anyString(), eq(500), eq("P1"), anyLong());
    }
}
