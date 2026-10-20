package com.marketingai.scheduler;

import com.marketingai.service.SelfReportCalibrationMath;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Weekly self-report calibration (roadmap 9-8; arch §16.4): Monday 06:30 UTC.
 * P(ACCRED_VERIFIED | self_accredited = yes) per creative × ticket bucket over 90 days →
 * tenants.value_coeffs.k_accr_by_creative. Creatives whose self-reported "accredited" leads rarely
 * verify get k_accr scaled down (max(0.2, rate/0.8)), so the Lead values sent to Meta stop rewarding them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SelfReportCalibrationScheduler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 30 6 * * MON", zone = "UTC")
    public void calibrateAll() {
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            try {
                int cells = calibrateTenant(tenantId);
                if (cells > 0) log.info("SelfReportCalibration tenant={} cells={}", tenantId, cells);
            } catch (Exception e) {
                log.warn("SelfReportCalibration tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Package-private for tests. Returns the number of calibrated cells written. */
    int calibrateTenant(UUID tenantId) throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT creative_id::text AS creative_id, ticket_bucket,
                       COUNT(*) AS n, COUNT(accreditation_verified_at) AS verified
                FROM sessions
                WHERE tenant_id = ? AND self_accredited = TRUE AND creative_id IS NOT NULL
                  AND started_at > now() - INTERVAL '90 days'
                GROUP BY creative_id, ticket_bucket
                """, tenantId);
        List<SelfReportCalibrationMath.Cell> cells = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            cells.add(new SelfReportCalibrationMath.Cell((String) r.get("creative_id"), (String) r.get("ticket_bucket"),
                    ((Number) r.get("n")).longValue(), ((Number) r.get("verified")).longValue()));
        }
        Map<String, Map<String, Object>> table = SelfReportCalibrationMath.build(cells);
        if (table.isEmpty()) return 0;
        jdbc.update("""
                UPDATE tenants
                SET value_coeffs = value_coeffs || jsonb_build_object('k_accr_by_creative', ?::jsonb,
                                                                       'k_accr_calibrated_at', now()::text)
                WHERE id = ?
                """, MAPPER.writeValueAsString(table), tenantId);
        return table.size();
    }
}
