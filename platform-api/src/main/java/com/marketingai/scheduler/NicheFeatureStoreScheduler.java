package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Niche feature store — monthly aggregation of first-party benchmarks.
 * vision §15.12.7: effect sizes + CI per niche × placement × device.
 * vision §25 p.19: FIRST_PARTY only until Meta Platform Terms legal check clears.
 * Aggregate floor: ≥5 tenants, otherwise is_published = false.
 * Runs 1st of month 06:00 UTC.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NicheFeatureStoreScheduler {

    private static final int MIN_TENANTS = 5;
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 6 1 * *", zone = "UTC")
    public void computeNicheBenchmarks() {
        // Aggregate bounce_rate per niche × placement × device across tenants
        // niche comes from tenants.settings->>'niche'
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    COALESCE(t.settings->>'niche', 'UNKNOWN')   AS niche,
                    COALESCE(s.utm_source, 'UNKNOWN')            AS placement,
                    CASE WHEN s.device_type IN ('MOBILE','DESKTOP','TABLET')
                         THEN s.device_type ELSE 'UNKNOWN' END   AS device_type,
                    'BOUNCE_RATE'                                 AS metric,
                    COUNT(DISTINCT t.id)                          AS n_tenants,
                    COUNT(*)                                      AS n_obs,
                    AVG(CASE WHEN s.bounce THEN 1.0 ELSE 0.0 END) AS mean_val,
                    STDDEV_POP(CASE WHEN s.bounce THEN 1.0 ELSE 0.0 END) AS std_val
                FROM sessions s
                JOIN tenants t ON s.tenant_id = t.id
                WHERE s.created_at >= NOW() - INTERVAL '90 days'
                  AND s.consent_level = 'full'
                  AND s.is_suspicious = false
                GROUP BY niche, placement, device_type
                HAVING COUNT(*) >= 50
                """);

        // Global mean for Cohen's d
        Map<String, Object> global = jdbc.queryForMap("""
                SELECT
                    AVG(CASE WHEN bounce THEN 1.0 ELSE 0.0 END) AS global_mean,
                    STDDEV_POP(CASE WHEN bounce THEN 1.0 ELSE 0.0 END) AS global_std,
                    COUNT(*) AS global_n
                FROM sessions
                WHERE created_at >= NOW() - INTERVAL '90 days'
                  AND consent_level = 'full'
                  AND is_suspicious = false
                """);

        double globalMean = global.get("global_mean") != null ? ((Number) global.get("global_mean")).doubleValue() : 0.55;
        double globalStd  = global.get("global_std")  != null ? ((Number) global.get("global_std")).doubleValue()  : 0.15;
        int    globalN    = global.get("global_n")    != null ? ((Number) global.get("global_n")).intValue()    : 0;

        for (Map<String, Object> row : rows) {
            String niche      = (String) row.get("niche");
            String placement  = (String) row.get("placement");
            String device     = (String) row.get("device_type");
            String metric     = (String) row.get("metric");
            int    nTenants   = ((Number) row.get("n_tenants")).intValue();
            int    nObs       = ((Number) row.get("n_obs")).intValue();
            double nicheMean  = ((Number) row.get("mean_val")).doubleValue();
            Double nicheStdRaw = row.get("std_val") != null ? ((Number) row.get("std_val")).doubleValue() : null;
            double nicheStd   = nicheStdRaw != null ? nicheStdRaw : nicheMean * 0.2 + 0.01;

            boolean published = nTenants >= MIN_TENANTS;
            double effectSize = computeCohensD(nicheMean, nicheStd, nObs, globalMean, globalStd, globalN);
            double se = computeCohensSe(nObs, globalN, effectSize);
            double ciLo = effectSize - 1.96 * se;
            double ciHi = effectSize + 1.96 * se;

            jdbc.update("""
                    INSERT INTO niche_benchmarks
                        (id, niche, placement, device_type, metric, effect_size, ci_lower, ci_upper,
                         n_tenants, n_observations, is_published, source, computed_at)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FIRST_PARTY', NOW())
                    ON CONFLICT (niche, placement, device_type, metric)
                    DO UPDATE SET
                        effect_size    = EXCLUDED.effect_size,
                        ci_lower       = EXCLUDED.ci_lower,
                        ci_upper       = EXCLUDED.ci_upper,
                        n_tenants      = EXCLUDED.n_tenants,
                        n_observations = EXCLUDED.n_observations,
                        is_published   = EXCLUDED.is_published,
                        computed_at    = NOW()
                    """,
                    niche, placement, device, metric,
                    effectSize, ciLo, ciHi, nTenants, nObs, published);
        }

        log.info("NicheFeatureStore: {} rows upserted", rows.size());
    }

    private double computeCohensD(double m1, double s1, int n1, double m2, double s2, int n2) {
        if (n1 < 2 || n2 < 2) return 0.0;
        double pooledVar = ((n1 - 1) * s1 * s1 + (n2 - 1) * s2 * s2) / (n1 + n2 - 2);
        double pooledSd  = Math.sqrt(Math.max(pooledVar, 1e-12));
        return (m1 - m2) / pooledSd;
    }

    private double computeCohensSe(int n1, int n2, double d) {
        if (n1 < 1 || n2 < 1) return 1.0;
        return Math.sqrt((double)(n1 + n2) / (n1 * n2) + d * d / (2.0 * (n1 + n2 - 2)));
    }
}
