package com.marketingai.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Roadmap 9-7: CAPI map per vision §19.4 and the predicted_value formula token. */
class ValueFormulaServiceTest {

    private final UUID tenant = UUID.randomUUID();

    private ValueFormulaService service(JdbcTemplate jdbc) {
        when(jdbc.queryForObject(contains("value_coeffs"), eq(String.class), eq(tenant)))
                .thenReturn("{\"k_ticket\":{\"LT25K\":0.3,\"50_100K\":1.0,\"GT250K\":3.0}}");
        return new ValueFormulaService(jdbc);
    }

    @Test
    void base_formula_multiplies_defaults_and_coefficients() {
        ValueFormulaService s = service(mock(JdbcTemplate.class));
        assertThat(s.computeValue(tenant, "base_lead*k_accr*k_ticket", true, "GT250K")).isEqualTo(12000.0);
        assertThat(s.computeValue(tenant, "base_lead*k_accr*k_ticket", false, "GT250K")).isEqualTo(0.0);
        assertThat(s.computeValue(tenant, "base_reg*k_ticket", true, "50_100K")).isEqualTo(15800.0);
    }

    @Test
    void predicted_value_token_resolves_from_the_session_prediction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ValueFormulaService s = service(jdbc);
        when(jdbc.queryForList(contains("lead_value_predictions"), eq(Double.class), eq(tenant), eq("sess-1")))
                .thenReturn(List.of(5250.0));
        assertThat(s.computeValue(tenant, "predicted_value", true, "GT250K", "sess-1")).isEqualTo(5250.0);
        assertThat(s.computeValue(tenant, "predicted_value*k_accr", true, "GT250K", "sess-1")).isEqualTo(5250.0);
        assertThat(s.computeValue(tenant, "predicted_value*k_accr", false, "GT250K", "sess-1")).isEqualTo(0.0);
    }

    @Test
    void predicted_value_falls_back_when_no_prediction_exists() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ValueFormulaService s = service(jdbc);
        when(jdbc.queryForList(contains("lead_value_predictions"), eq(Double.class), any(), any())).thenReturn(List.of());
        // token alone → the lead formula; token as a factor → the other factors only
        assertThat(s.computeValue(tenant, "predicted_value", true, "GT250K", "sess-2")).isEqualTo(12000.0);
        assertThat(s.computeValue(tenant, "predicted_value*k_ticket", true, "LT25K", "sess-2")).isEqualTo(0.3);
        assertThat(s.computeValue(tenant, "predicted_value", true, "GT250K", null)).isEqualTo(12000.0);
    }

    @Test
    void investor_seed_follows_vision_19_4() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new ValueFormulaService(jdbc).seedInvestorCAPIMap(tenant);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(6)).update(contains("INSERT INTO capi_event_map"), args.capture());
        List<Object[]> rows = args.getAllValues();
        assertThat(rows).extracting(r -> r[1] + ":" + r[2] + ":" + r[3]).containsExactly(
                "FORM_SUBMIT:investor_primary:Lead",
                "FORM_SUBMIT:future_regcf:Lead",
                "BOOKED:investor_primary:Schedule",
                "CALL_HELD:investor_primary:CompleteRegistration",
                "ACCRED_VERIFIED:investor_primary:AccreditationVerified",
                "FUNDS_RECEIVED:investor_primary:Purchase");
        assertThat(rows.get(0)[4]).isEqualTo("base_lead*k_accr*k_ticket");
        assertThat(rows.get(2)[4]).isEqualTo("base_schedule*k_ticket");
        assertThat(rows.get(3)[4]).isEqualTo("base_reg*k_ticket");
        assertThat(rows.get(5)[4]).isEqualTo("monetaryValue");
        assertThat(rows).extracting(r -> (String) r[1]).doesNotContain("INVEST_COMMITTED");
    }
}
