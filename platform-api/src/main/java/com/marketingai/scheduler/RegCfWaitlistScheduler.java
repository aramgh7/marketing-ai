package com.marketingai.scheduler;

import com.marketingai.service.MlServiceClient;
import com.marketingai.service.RegCfWaitlistMath;
import com.marketingai.util.Jsonb;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly Reg CF waitlist valuation (roadmap 9-8; arch §16.20): Monday 06:00 UTC → one
 * {@code waitlist_value_estimates} row per tenant and week. ⚠️ Valuation only — the Meta audience
 * stays frozen until the OI-3 gate is ACTIVE; this job never touches audiences.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegCfWaitlistScheduler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;

    @Scheduled(cron = "0 0 6 * * MON", zone = "UTC")
    public void estimateAll() {
        List<Map<String, Object>> tenants = jdbc.queryForList("SELECT id, settings FROM tenants");
        for (Map<String, Object> t : tenants) {
            try {
                estimateTenant((UUID) t.get("id"), Jsonb.asString(t.get("settings")));
            } catch (Exception e) {
                log.warn("RegCfWaitlist tenant={} failed: {}", t.get("id"), e.getMessage());
            }
        }
    }

    /** Package-private for tests. Returns the stored point estimate. */
    @SuppressWarnings("unchecked")
    double estimateTenant(UUID tenantId, String settingsJson) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regcf_waitlist WHERE tenant_id = ? AND status != 'REMOVED'", Integer.class, tenantId);
        int nLeads = n == null ? 0 : n;
        double pConvert = 0.05, avgTicket = 10_000.0;
        try {
            JsonNode s = settingsJson == null ? MAPPER.createObjectNode() : MAPPER.readTree(settingsJson);
            pConvert = s.path("regcf_p_convert").asDouble(pConvert);
            avgTicket = s.path("regcf_avg_ticket").asDouble(avgTicket);
        } catch (Exception ignored) { }

        RegCfWaitlistMath.Estimate est = RegCfWaitlistMath.estimate(nLeads, pConvert, avgTicket);
        Double ciLower = est.ciLower(), ciUpper = est.ciUpper();
        try {
            Map<String, Object> ml = mlClient.post("/waitlist/value",
                    Map.of("n_leads", nLeads, "p_convert_regcf", pConvert, "avg_ticket_regcf", avgTicket), Map.class);
            if (ml != null && ml.get("ci_lower") instanceof Number lo && ml.get("ci_upper") instanceof Number hi) {
                ciLower = lo.doubleValue();
                ciUpper = hi.doubleValue();
            }
        } catch (Exception e) {
            log.debug("RegCfWaitlist ML unavailable, Wilson fallback: {}", e.getMessage());
        }

        jdbc.update("""
                INSERT INTO waitlist_value_estimates (tenant_id, estimated_at, n_leads, p_convert_regcf, avg_ticket_regcf, ci_lower, ci_upper)
                VALUES (?, CURRENT_DATE, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, estimated_at) DO UPDATE SET
                    n_leads = EXCLUDED.n_leads, p_convert_regcf = EXCLUDED.p_convert_regcf,
                    avg_ticket_regcf = EXCLUDED.avg_ticket_regcf, ci_lower = EXCLUDED.ci_lower, ci_upper = EXCLUDED.ci_upper
                """, tenantId, nLeads, (float) pConvert, (float) avgTicket, ciLower.floatValue(), ciUpper.floatValue());
        log.info("RegCfWaitlist tenant={} n={} value={} (frozen: OI-3)", tenantId, nLeads, est.value());
        return est.value();
    }
}
