package com.marketingai.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Proxy Calibration Scheduler — weekly Pearson + Spearman correlation
 * between sessions.quality_score and actual conversion (3-21).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyCalibrationScheduler {

    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 0 3 * * MON")
    public void calibrateProxies() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT id FROM tenants");

        for (Map<String, Object> tenant : tenants) {
            UUID tenantId = (UUID) tenant.get("id");
            try {
                calibrateForTenant(tenantId);
            } catch (Exception e) {
                log.warn("ProxyCalibrationScheduler failed for tenant={}: {}", tenantId, e.getMessage());
            }
        }
    }

    private void calibrateForTenant(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT quality_score,
                       CASE WHEN converted THEN 1.0 ELSE 0.0 END AS converted
                FROM sessions
                WHERE tenant_id = ?
                  AND started_at > now() - interval '30 days'
                  AND quality_score IS NOT NULL
                LIMIT 10000
                """, tenantId);

        if (rows.size() < 30) {
            log.debug("ProxyCalibration: insufficient data for tenant={} (n={})", tenantId, rows.size());
            return;
        }

        double[] qs = rows.stream()
                .mapToDouble(r -> ((Number) r.get("quality_score")).doubleValue()).toArray();
        double[] cv = rows.stream()
                .mapToDouble(r -> ((Number) r.get("converted")).doubleValue()).toArray();

        double pearsonR = pearson(qs, cv);
        double spearmanR = spearman(qs, cv);
        int n = rows.size();

        jdbc.update("""
                INSERT INTO proxy_calibration_runs
                (tenant_id, run_date, stage, proxy_col, target_col, sample_n, pearson_r, spearman_r)
                VALUES (?, CURRENT_DATE, 'quality_score', 'quality_score', 'converted', ?, ?, ?)
                """, tenantId, n, (float) pearsonR, (float) spearmanR);

        log.info("ProxyCalibration: tenant={} n={} pearson={} spearman={}",
                tenantId, n, pearsonR, spearmanR);
    }

    public static double pearson(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < n; i++) {
            sumX  += x[i];
            sumY  += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }
        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den < 1e-10 ? 0.0 : num / den;
    }

    public static double spearman(double[] x, double[] y) {
        return pearson(rankTransform(x), rankTransform(y));
    }

    private static double[] rankTransform(double[] arr) {
        int n = arr.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(arr[a], arr[b]));
        double[] ranks = new double[n];
        for (int i = 0; i < n; i++) ranks[idx[i]] = i + 1.0;
        return ranks;
    }
}
