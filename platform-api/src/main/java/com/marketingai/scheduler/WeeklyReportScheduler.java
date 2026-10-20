package com.marketingai.scheduler;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import com.marketingai.service.NoshowStats;
import com.marketingai.config.AppProperties;
import com.marketingai.dto.analytics.AtomPerformanceRow;
import com.marketingai.dto.analytics.CreativePerformanceRow;
import com.marketingai.dto.analytics.FunnelResponse;
import com.marketingai.dto.analytics.OverviewResponse;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.TenantRepository;
import com.marketingai.security.TenantContext;
import com.marketingai.service.AnalyticsService;
import com.marketingai.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeeklyReportScheduler {

    private final TenantRepository tenantRepository;
    private final AnalyticsService analyticsService;
    private final AlertService alertService;
    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private JavaMailSender mailSender;

    /** Every Monday at 08:00 UTC */
    @Scheduled(cron = "0 0 8 * * MON", zone = "UTC")
    public void sendWeeklyReports() {
        if (!appProperties.getReport().isEnabled()) {
            log.info("Weekly reports disabled — skipping");
            return;
        }

        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Weekly report run: {} tenants", tenants.size());

        for (Tenant tenant : tenants) {
            try {
                TenantContext.set(tenant.getId().toString());
                sendReportForTenant(tenant);
            } catch (Exception e) {
                log.error("Weekly report failed for tenant {}: {}", tenant.getId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void sendReportForTenant(Tenant tenant) {
        OverviewResponse overview = analyticsService.overview(7);
        List<CreativePerformanceRow> creatives = analyticsService.creativePerformance(7);
        List<AtomPerformanceRow> atoms = analyticsService.atomPerformance(7, null);
        FunnelResponse funnel = analyticsService.funnel(7);

        String report = buildReport(tenant, overview, creatives, atoms, funnel);

        // Enrich with explain-service narrative (vision §1.5 weekly report)
        String narrative = fetchNarrative(overview, funnel);
        if (narrative != null && !narrative.isBlank()) {
            report = report + "\n─── AI Narrative ───\n" + narrative + "\n";
        }

        boolean sentTelegram = sendTelegram(report);
        boolean sentEmail    = sendEmail(tenant, report);

        if (!sentTelegram && !sentEmail) {
            log.warn("No delivery channel configured for tenant {} — weekly report not sent",
                    tenant.getId());
        }
    }

    // ─── Report builder ───────────────────────────────────────────────────────

    /** No-show rate over the last 7 days with a 90 % credible interval vs the client's target (< 30 %). */
    String noShowLine(Tenant tenant) {
        try {
            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT COUNT(*) FILTER (WHERE status = 'NO_SHOW')  AS no_show,
                           COUNT(*) FILTER (WHERE status = 'COMPLETED') AS held
                    FROM appointments
                    WHERE tenant_id = ? AND scheduled_at >= now() - INTERVAL '7 days'
                    """, tenant.getId());
            Double target = null;
            try {
                Object t = com.marketingai.util.Jsonb.asMap(tenant.getSettings()).get("noshow_target");
                if (t != null) target = Double.parseDouble(t.toString());
            } catch (Exception ignored) { /* default target */ }
            NoshowStats.Summary summary = NoshowStats.summarise(
                    ((Number) row.get("no_show")).longValue(), ((Number) row.get("held")).longValue(), target);
            return NoshowStats.reportLine(summary);
        } catch (Exception e) {
            return "No-show rate: unavailable (" + e.getMessage() + ")";
        }
    }

    private String buildReport(Tenant tenant, OverviewResponse ov,
                                List<CreativePerformanceRow> creatives,
                                List<AtomPerformanceRow> atoms,
                                FunnelResponse funnel) {
        var sb = new StringBuilder();
        sb.append("📊 Weekly Marketing Report — ").append(tenant.getName()).append("\n");
        sb.append("Period: last 7 days\n\n");

        // Overview
        sb.append("─── Overview ───\n");
        sb.append(String.format("Sessions:    %,d\n", ov.getTotalSessions()));
        sb.append(String.format("Conversions: %,d\n", ov.getTotalConversions()));
        if (ov.getConversionRate() != null) {
            sb.append(String.format("CVR:         %.2f%%\n", ov.getConversionRate() * 100));
        }
        sb.append(String.format("Revenue:     $%.2f\n", ov.getTotalRevenue()));
        sb.append(String.format("Creatives:   %d active, %d analyzed\n\n",
                ov.getActiveCreatives(), ov.getAnalyzedCreatives()));

        // Top 3 creatives by sessions
        sb.append("─── Top Creatives (by sessions) ───\n");
        creatives.stream().limit(3).forEach(c -> {
            String cvr = c.getConversionRate() != null
                    ? String.format("%.2f%%", c.getConversionRate() * 100) : "n/a";
            sb.append(String.format("• %s [%s] — %,d sessions, CVR %s, $%.0f revenue\n",
                    c.getTitle() != null ? c.getTitle() : "Untitled",
                    c.getType(), c.getSessions(), cvr, c.getRevenue()));
        });
        if (creatives.isEmpty()) sb.append("  (no data yet)\n");
        sb.append("\n");

        // Top atom combos by CVR
        sb.append("─── Top Atom Insights ───\n");
        atoms.stream()
                .filter(a -> a.getConversionRate() != null && a.getTotalSessions() >= 10)
                .sorted((a, b) -> Double.compare(b.getConversionRate(), a.getConversionRate()))
                .limit(5)
                .forEach(a -> sb.append(String.format("• %s=%s → CVR %.2f%% (%d sessions)\n",
                        a.getAtomType(), a.getAtomValue(),
                        a.getConversionRate() * 100, a.getTotalSessions())));
        if (atoms.stream().noneMatch(a -> a.getConversionRate() != null)) {
            sb.append("  (no event data for atom insights yet)\n");
        }
        sb.append("\n");

        // Funnel anomalies from diagnostic card
        sb.append("─── Funnel Diagnostics ───\n");
        funnel.getDiagnostics().getSymptoms().stream()
                .filter(FunnelResponse.Symptom::isDetected)
                .forEach(s -> sb.append(String.format("⚠️ %s: %s\n   → %s\n",
                        s.getCode(), s.getDiagnosis(), s.getAction())));
        if (funnel.getDiagnostics().getSymptomsDetected() == 0) {
            sb.append("  ✅ No anomalies detected.\n");
        }
        sb.append("\n");

        sb.append("─── Appointments (roadmap 8-3) ───\n");
        sb.append(noShowLine(tenant)).append("\n\n");

        sb.append("─── Note ───\n");
        sb.append(AnalyticsService.DISCLAIMER).append("\n");

        return sb.toString();
    }

    // ─── Explain service narrative ────────────────────────────────────────────

    private String fetchNarrative(OverviewResponse ov, FunnelResponse funnel) {
        String mlUrl = appProperties.getMlServiceUrl();
        if (mlUrl == null || mlUrl.isBlank()) return null;
        try {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("sessions",       ov.getTotalSessions());
            stats.put("conversions",    ov.getTotalConversions());
            stats.put("cvr",            ov.getConversionRate());
            stats.put("revenue",        ov.getTotalRevenue());
            stats.put("symptoms_detected", funnel.getDiagnostics().getSymptomsDetected());

            Map<String, Object> req = Map.of("stats", stats, "raw", false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());

            @SuppressWarnings("unchecked")
            Map<String, Object> result = restTemplate.postForObject(
                    mlUrl + "/explain/campaign",
                    new HttpEntity<>(req, headers), Map.class);
            return result != null ? (String) result.get("explanation") : null;
        } catch (Exception e) {
            log.warn("Explain service unavailable for weekly report: {}", e.getMessage());
            return null;
        }
    }

    // ─── Delivery ─────────────────────────────────────────────────────────────

    private boolean sendTelegram(String text) {
        AppProperties.Telegram cfg = appProperties.getTelegram();
        String slackUrl = appProperties.getSlackWebhookUrl();
        if (!cfg.isConfigured() && (slackUrl == null || slackUrl.isBlank())) return false;
        try {
            alertService.sendAlert(text);
            return true;
        } catch (Exception e) {
            log.error("Alert weekly report failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean sendEmail(Tenant tenant, String text) {
        AppProperties.Report reportCfg = appProperties.getReport();
        if (mailSender == null || !reportCfg.isEmailConfigured()) return false;
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setFrom(reportCfg.getEmailFrom());
            msg.setTo(reportCfg.getEmailTo());
            msg.setSubject("Weekly Report — " + tenant.getName());
            msg.setText(text);
            mailSender.send(msg);
            log.info("Weekly report email sent for tenant {}", tenant.getId());
            return true;
        } catch (Exception e) {
            log.error("Email weekly report failed for tenant {}: {}", tenant.getId(), e.getMessage());
            return false;
        }
    }
}
