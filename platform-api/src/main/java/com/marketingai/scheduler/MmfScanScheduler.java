package com.marketingai.scheduler;

import com.marketingai.service.AlertService;
import com.marketingai.service.MlServiceClient;
import com.marketingai.service.MmfScanMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily Message-Market Fit scan (roadmap 9-8; arch §9 / §15.6). 05:30 UTC, per ACTIVE campaign
 * (the audience key): campaign aggregates → ml-service {@code POST /mmf/diagnose} → {@code mmf_diagnoses};
 * OFFER_PROBLEM raises an alert. The on-demand {@code POST /api/v1/mmf/diagnose} stays as is.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MmfScanScheduler {

    private final JdbcTemplate jdbc;
    private final MlServiceClient mlClient;
    private final AlertService alertService;

    @Scheduled(cron = "0 30 5 * * *", zone = "UTC")
    public void scan() {
        List<Map<String, Object>> campaigns = jdbc.queryForList("""
                SELECT c.id, c.tenant_id, c.name, c.learning_phase, c.daily_budget, c.spend_today, c.spend_total,
                       COALESCE(SUM(a.impressions_total), 0) AS impressions,
                       COALESCE(SUM(a.clicks_total), 0)      AS clicks,
                       COALESCE(SUM(a.conversions_total), 0) AS conversions
                FROM meta_campaigns c
                LEFT JOIN meta_ad_sets a ON a.campaign_id = c.id AND a.tenant_id = c.tenant_id
                WHERE c.status = 'ACTIVE'
                GROUP BY c.id, c.tenant_id, c.name, c.learning_phase, c.daily_budget, c.spend_today, c.spend_total
                """);
        int stored = 0;
        for (Map<String, Object> c : campaigns) {
            try {
                if (diagnoseCampaign(c)) stored++;
            } catch (Exception e) {
                log.warn("MmfScan campaign={} failed: {}", c.get("id"), e.getMessage());
            }
        }
        log.info("MmfScanScheduler: {} campaigns scanned, {} diagnoses stored", campaigns.size(), stored);
    }

    /** Package-private for tests: shapes metrics, calls ML, stores the diagnosis. */
    @SuppressWarnings("unchecked")
    boolean diagnoseCampaign(Map<String, Object> c) {
        long impressions = ((Number) c.get("impressions")).longValue();
        long clicks = ((Number) c.get("clicks")).longValue();
        if (!MmfScanMath.enoughData(impressions, clicks)) return false;
        UUID tenantId = (UUID) c.get("tenant_id");
        UUID campaignId = (UUID) c.get("id");

        Map<String, Object> metrics = MmfScanMath.metrics(impressions, clicks,
                ((Number) c.get("conversions")).longValue(),
                c.get("spend_total") instanceof Number n ? n.doubleValue() : 0.0,
                c.get("spend_today") instanceof Number n2 ? n2.doubleValue() : 0.0,
                c.get("daily_budget") instanceof Number n3 ? n3.doubleValue() : null,
                (String) c.get("learning_phase"), null);

        Map<String, Object> ml = mlClient.post("/mmf/diagnose", metrics, Map.class);
        if (ml == null) return false;
        String diagnosis = String.valueOf(ml.getOrDefault("diagnosis", "HEALTHY"));
        double confidence = ml.get("confidence") instanceof Number n ? n.doubleValue() : 0.5;
        jdbc.update("""
                INSERT INTO mmf_diagnoses (tenant_id, audience_key, verdict, p_any_above_breakeven, signals)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, tenantId, campaignId.toString(), diagnosis, (float) confidence, toJson(metrics, ml));
        if ("OFFER_PROBLEM".equals(diagnosis)) {
            alertService.sendAlert("⚠️ Message-Market Fit: campaign \"" + c.get("name") + "\" diagnosed OFFER_PROBLEM "
                    + "(confidence " + Math.round(confidence * 100) + "%). Review the offer before adding creatives.");
        }
        return true;
    }

    private static String toJson(Map<String, Object> metrics, Map<String, Object> ml) {
        StringBuilder sb = new StringBuilder("{\"metrics\": {");
        boolean first = true;
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append('"').append(e.getKey()).append("\": ");
            Object v = e.getValue();
            if (v instanceof Number) sb.append(v); else sb.append('"').append(String.valueOf(v).replace("\"", "'")).append('"');
        }
        sb.append("}, \"source\": \"MmfScanScheduler\", \"diagnosis\": \"")
          .append(String.valueOf(ml.getOrDefault("diagnosis", "")).replace("\"", "'")).append("\"}");
        return sb.toString();
    }
}
