package com.marketingai.service;

import com.marketingai.dto.analytics.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final NamedParameterJdbcTemplate jdbc;

    public static final String DISCLAIMER =
            "Phase 1 analytics: descriptive only. " +
            "Meta metrics (spend, ROAS, impressions, CTR, hook rate) require Phase 3 integration.";

    // ─── Overview ────────────────────────────────────────────────────────────

    public OverviewResponse overview(int days) {
        var params = new MapSqlParameterSource("days", days);

        Map<String, Object> eventStats = jdbc.queryForMap("""
                SELECT
                    COUNT(DISTINCT session_id)                                         AS total_sessions,
                    COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE'))   AS total_conversions,
                    COALESCE(SUM(CASE WHEN event_type IN ('CONVERSION','PURCHASE')
                                     THEN revenue ELSE 0 END), 0)                     AS total_revenue
                FROM events
                WHERE is_suspicious = false
                  AND event_time >= NOW() - :days * INTERVAL '1 day'
                """, params);

        Map<String, Object> creativeStats = jdbc.queryForMap("""
                SELECT
                    COUNT(*) FILTER (WHERE status != 'ARCHIVED') AS active_creatives,
                    COUNT(*) FILTER (WHERE status = 'ACTIVE')    AS analyzed_creatives
                FROM creatives
                """, new MapSqlParameterSource());

        long totalSessions = toLong(eventStats.get("total_sessions"));
        long totalConversions = toLong(eventStats.get("total_conversions"));

        return OverviewResponse.builder()
                .periodDays(days)
                .totalSessions(totalSessions)
                .totalConversions(totalConversions)
                .conversionRate(totalSessions > 0 ? (double) totalConversions / totalSessions : null)
                .totalRevenue(toDouble(eventStats.get("total_revenue")))
                .activeCreatives(toLong(creativeStats.get("active_creatives")))
                .analyzedCreatives(toLong(creativeStats.get("analyzed_creatives")))
                .disclaimer(DISCLAIMER)
                .build();
    }

    // ─── Per-creative performance ─────────────────────────────────────────────

    public List<CreativePerformanceRow> creativePerformance(int days) {
        var params = new MapSqlParameterSource("days", days);

        return jdbc.query("""
                SELECT
                    c.id                AS creative_id,
                    c.title,
                    c.type,
                    c.status,
                    c.is_anchor,
                    COUNT(DISTINCT ca.id)   AS atoms_count,
                    COALESCE(e.sessions,  0)    AS sessions,
                    COALESCE(e.conversions, 0)  AS conversions,
                    COALESCE(e.revenue,   0)    AS revenue
                FROM creatives c
                LEFT JOIN creative_atoms ca ON ca.creative_id = c.id
                LEFT JOIN (
                    SELECT
                        creative_id,
                        COUNT(DISTINCT session_id)                                        AS sessions,
                        COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE'))  AS conversions,
                        COALESCE(SUM(CASE WHEN event_type IN ('CONVERSION','PURCHASE')
                                         THEN revenue ELSE 0 END), 0)                    AS revenue
                    FROM events
                    WHERE is_suspicious = false
                      AND event_time >= NOW() - :days * INTERVAL '1 day'
                    GROUP BY creative_id
                ) e ON e.creative_id = c.id
                WHERE c.status != 'ARCHIVED'
                GROUP BY c.id, c.title, c.type, c.status, c.is_anchor,
                         e.sessions, e.conversions, e.revenue
                ORDER BY COALESCE(e.sessions, 0) DESC, c.created_at DESC
                """, params, (rs, i) -> {
            long sessions = rs.getLong("sessions");
            long conversions = rs.getLong("conversions");
            return CreativePerformanceRow.builder()
                    .creativeId(UUID.fromString(rs.getString("creative_id")))
                    .title(rs.getString("title"))
                    .type(rs.getString("type"))
                    .status(rs.getString("status"))
                    .isAnchor(rs.getBoolean("is_anchor"))
                    .atomsCount(rs.getInt("atoms_count"))
                    .sessions(sessions)
                    .conversions(conversions)
                    .conversionRate(sessions > 0 ? (double) conversions / sessions : null)
                    .revenue(rs.getDouble("revenue"))
                    .build(); // spend/roas/impressions/ctr stay null (Phase 3)
        });
    }

    // ─── Atom-level performance ───────────────────────────────────────────────

    public List<AtomPerformanceRow> atomPerformance(int days, String atomType) {
        var params = new MapSqlParameterSource("days", days);
        String typeFilter = (atomType != null && !atomType.isBlank()) ? atomType.toUpperCase() : null;
        params.addValue("atomType", typeFilter);

        return jdbc.query("""
                SELECT
                    ca.atom_type,
                    ca.atom_value,
                    COUNT(DISTINCT ca.creative_id)                                    AS creative_count,
                    COALESCE(SUM(e.sessions),     0)                                  AS total_sessions,
                    COALESCE(SUM(e.conversions),  0)                                  AS conversions,
                    CASE WHEN COALESCE(SUM(e.sessions), 0) > 0
                         THEN COALESCE(SUM(e.conversions), 0)::FLOAT / SUM(e.sessions)
                         ELSE NULL END                                                AS conversion_rate,
                    AVG(CASE WHEN COALESCE(e.conversions, 0) > 0
                             THEN e.revenue ELSE NULL END)                            AS avg_revenue
                FROM creative_atoms ca
                JOIN creatives c ON c.id = ca.creative_id AND c.status != 'ARCHIVED'
                LEFT JOIN (
                    SELECT
                        creative_id,
                        COUNT(DISTINCT session_id)                                        AS sessions,
                        COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE'))  AS conversions,
                        COALESCE(SUM(CASE WHEN event_type IN ('CONVERSION','PURCHASE')
                                         THEN revenue ELSE 0 END), 0)                    AS revenue
                    FROM events
                    WHERE is_suspicious = false
                      AND event_time >= NOW() - :days * INTERVAL '1 day'
                    GROUP BY creative_id
                ) e ON e.creative_id = ca.creative_id
                WHERE (:atomType IS NULL OR ca.atom_type = :atomType)
                GROUP BY ca.atom_type, ca.atom_value
                ORDER BY ca.atom_type, conversion_rate DESC NULLS LAST, total_sessions DESC
                """, params, (rs, i) -> AtomPerformanceRow.builder()
                        .atomType(rs.getString("atom_type"))
                        .atomValue(rs.getString("atom_value"))
                        .creativeCount(rs.getInt("creative_count"))
                        .totalSessions(rs.getLong("total_sessions"))
                        .conversions(rs.getLong("conversions"))
                        .conversionRate(toDoubleNullable(rs.getObject("conversion_rate")))
                        .avgRevenue(toDoubleNullable(rs.getObject("avg_revenue")))
                        .build());
    }

    // ─── Funnel + §3.2 diagnostic card ───────────────────────────────────────

    public FunnelResponse funnel(int days) {
        var params = new MapSqlParameterSource("days", days);

        // Stage counts
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT event_type, COUNT(DISTINCT session_id) AS cnt
                FROM events
                WHERE is_suspicious = false
                  AND event_time >= NOW() - :days * INTERVAL '1 day'
                  AND event_type IN ('PAGE_VIEW','SCROLL','FORM_START','FORM_SUBMIT','CONVERSION','PURCHASE')
                GROUP BY event_type
                """, params);

        Map<String, Long> counts = new java.util.HashMap<>();
        for (var r : rows) {
            counts.put((String) r.get("event_type"), toLong(r.get("cnt")));
        }

        // Bounce rate: sessions with PAGE_VIEW but no SCROLL
        Map<String, Object> bounceStats = jdbc.queryForMap("""
                SELECT
                    COUNT(DISTINCT s.session_id)
                        FILTER (WHERE s.event_count = 1 AND NOT s.has_scroll) AS bounced,
                    COUNT(DISTINCT s.session_id)                              AS total
                FROM (
                    SELECT
                        session_id,
                        COUNT(*)                          AS event_count,
                        BOOL_OR(event_type = 'SCROLL')   AS has_scroll
                    FROM events
                    WHERE is_suspicious = false
                      AND event_time >= NOW() - :days * INTERVAL '1 day'
                    GROUP BY session_id
                ) s
                """, params);

        long bounced = toLong(bounceStats.get("bounced"));
        long totalSessions = toLong(bounceStats.get("total"));
        double bounceRate = totalSessions > 0 ? (double) bounced / totalSessions : 0;

        // Build funnel stages in order
        String[] order = {"PAGE_VIEW", "SCROLL", "FORM_START", "FORM_SUBMIT", "CONVERSION", "PURCHASE"};
        List<FunnelResponse.FunnelStage> stages = new ArrayList<>();
        Long prev = null;
        for (String stage : order) {
            long cnt = counts.getOrDefault(stage, 0L);
            Double drop = (prev != null && prev > 0) ? 1.0 - (double) cnt / prev : null;
            stages.add(FunnelResponse.FunnelStage.builder()
                    .stage(stage)
                    .sessions(cnt)
                    .dropOffPct(drop)
                    .build());
            if (cnt > 0) prev = cnt;
        }

        // §3.2 Diagnostic card — Phase 1 detectable symptoms
        List<FunnelResponse.Symptom> symptoms = buildSymptoms(counts, bounceRate, totalSessions);
        int detected = (int) symptoms.stream().filter(FunnelResponse.Symptom::isDetected).count();

        FunnelResponse.DiagnosticCard card = FunnelResponse.DiagnosticCard.builder()
                .symptomsDetected(detected)
                .symptoms(symptoms)
                .note("Hook Rate, CTR, Watch Rate require Meta API (Phase 3). " +
                      "Current diagnostics are based on first-party event data only.")
                .build();

        return FunnelResponse.builder()
                .periodDays(days)
                .stages(stages)
                .diagnostics(card)
                .disclaimer(DISCLAIMER)
                .build();
    }

    // ─── §3.2 Symptom evaluation ──────────────────────────────────────────────

    private List<FunnelResponse.Symptom> buildSymptoms(Map<String, Long> counts,
                                                        double bounceRate,
                                                        long totalSessions) {
        List<FunnelResponse.Symptom> symptoms = new ArrayList<>();

        long pageViews  = counts.getOrDefault("PAGE_VIEW",  0L);
        long scrolls    = counts.getOrDefault("SCROLL",     0L);
        long formStarts = counts.getOrDefault("FORM_START", 0L);
        long formSubs   = counts.getOrDefault("FORM_SUBMIT",0L);
        long conversions = counts.getOrDefault("CONVERSION",0L)
                         + counts.getOrDefault("PURCHASE",  0L);

        // Symptom: High bounce (PAGE_VIEW → SCROLL drop > 60%)
        boolean highBounce = pageViews > 50 && bounceRate > 0.60;
        symptoms.add(FunnelResponse.Symptom.builder()
                .code("HIGH_BOUNCE")
                .detected(highBounce)
                .value(String.format("%.0f%%", bounceRate * 100))
                .diagnosis("Message mismatch: creative promise ≠ landing reality (§3.2)")
                .action("Sync landing headline with creative hook atom")
                .dataSource("first-party events")
                .build());

        // Symptom: Form abandonment (FORM_START → FORM_SUBMIT drop > 50%)
        boolean highFormAbandon = formStarts > 20
                && formSubs < formStarts * 0.5;
        String formAbandonValue = formStarts > 0
                ? String.format("%.0f%% complete", formStarts > 0 ? (double) formSubs / formStarts * 100 : 0)
                : "n/a";
        symptoms.add(FunnelResponse.Symptom.builder()
                .code("HIGH_FORM_ABANDON")
                .detected(highFormAbandon)
                .value(formAbandonValue)
                .diagnosis("Form friction: too many fields or steps")
                .action("Reduce form fields, add progress indicator")
                .dataSource("first-party events")
                .build());

        // Symptom: Low CVR overall (< 1%)
        double cvr = totalSessions > 0 ? (double) conversions / totalSessions : 0;
        boolean lowCvr = totalSessions > 100 && cvr < 0.01;
        symptoms.add(FunnelResponse.Symptom.builder()
                .code("LOW_CVR")
                .detected(lowCvr)
                .value(String.format("%.2f%%", cvr * 100))
                .diagnosis("Offer or trust issue: visitors scroll but don't convert")
                .action("Review offer atoms (proof_type, cta) and landing blocks (pricing, testimonials)")
                .dataSource("first-party events")
                .build());

        // Symptom: Weak CTA signal (scroll but no form_start — drop > 80%)
        boolean weakCta = scrolls > 50
                && formStarts < scrolls * 0.20
                && scrolls > 0;
        symptoms.add(FunnelResponse.Symptom.builder()
                .code("WEAK_CTA")
                .detected(weakCta)
                .value(scrolls > 0 ? String.format("%.0f%% reach form", (double) formStarts / scrolls * 100) : "n/a")
                .diagnosis("Weak CTA or unpersuasive offer: users scroll but don't start the form")
                .action("Test CTA atom variants; strengthen offer (discount, guarantee)")
                .dataSource("first-party events")
                .build());

        // Phase 3 placeholder symptoms (data not available yet)
        symptoms.add(FunnelResponse.Symptom.builder()
                .code("LOW_HOOK_RATE")
                .detected(false)
                .value("n/a — needs Meta API")
                .diagnosis("Hook doesn't grab attention in first 3 seconds")
                .action("Rotate hook atom (Phase 3: Meta 3-sec view data required)")
                .dataSource("Meta API (Phase 3)")
                .build());

        symptoms.add(FunnelResponse.Symptom.builder()
                .code("LOW_CTR")
                .detected(false)
                .value("n/a — needs Meta API")
                .diagnosis("Weak CTA or offer in creative")
                .action("Test CTA and offer sub-atoms (Phase 3: Meta CTR data required)")
                .dataSource("Meta API (Phase 3)")
                .build());

        return symptoms;
    }

    // ─── A/B Dashboard (2-7) ─────────────────────────────────────────────────

    public List<AbVariantRow> abDashboard(int days) {
        var params = new MapSqlParameterSource("days", days);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    ta.variant_id,
                    lv.name,
                    lv.slug,
                    lv.status,
                    ta.alpha,
                    ta.beta,
                    ta.total_samples,
                    COALESCE(SUM(e.sessions),     0) AS sessions,
                    COALESCE(SUM(e.conversions),  0) AS conversions
                FROM thompson_arms ta
                JOIN landing_variants lv ON lv.id = ta.variant_id
                LEFT JOIN (
                    SELECT
                        variant_id,
                        COUNT(DISTINCT session_id)                                        AS sessions,
                        COUNT(*) FILTER (WHERE event_type IN ('CONVERSION','PURCHASE',
                                                              'FORM_SUBMIT'))             AS conversions
                    FROM events
                    WHERE is_suspicious = false
                      AND variant_id IS NOT NULL
                      AND event_time >= NOW() - :days * INTERVAL '1 day'
                    GROUP BY variant_id
                ) e ON e.variant_id = ta.variant_id
                WHERE lv.status IN ('ACTIVE','PAUSED')
                GROUP BY ta.variant_id, lv.name, lv.slug, lv.status,
                         ta.alpha, ta.beta, ta.total_samples
                ORDER BY ta.alpha / (ta.alpha + ta.beta) DESC
                """, params);

        if (rows.isEmpty()) return List.of();

        // Monte Carlo P(best): sample Beta(α,β) N=5000 times per arm
        int N = 5_000;
        int n = rows.size();
        double[] alpha = new double[n];
        double[] beta  = new double[n];
        int[] wins = new int[n];

        for (int i = 0; i < n; i++) {
            alpha[i] = toDouble(rows.get(i).get("alpha"));
            beta[i]  = toDouble(rows.get(i).get("beta"));
        }

        var rng = RandomGenerator.getDefault();
        for (int s = 0; s < N; s++) {
            int bestIdx = 0;
            double bestVal = -1.0;
            for (int i = 0; i < n; i++) {
                double val = sampleBeta(alpha[i], beta[i], rng);
                if (val > bestVal) { bestVal = val; bestIdx = i; }
            }
            wins[bestIdx]++;
        }

        List<AbVariantRow> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = rows.get(i);
            long sessions    = toLong(r.get("sessions"));
            long conversions = toLong(r.get("conversions"));
            result.add(AbVariantRow.builder()
                    .variantId(UUID.fromString(r.get("variant_id").toString()))
                    .name((String) r.get("name"))
                    .slug((String) r.get("slug"))
                    .status((String) r.get("status"))
                    .alpha(alpha[i])
                    .beta(beta[i])
                    .totalSamples(((Number) r.get("total_samples")).intValue())
                    .estimatedCvr(alpha[i] / (alpha[i] + beta[i]))
                    .sessions(sessions)
                    .conversions(conversions)
                    .observedCvr(sessions > 0 ? (double) conversions / sessions : null)
                    .probBest((double) wins[i] / N)
                    .build());
        }
        // Re-sort by probBest descending
        result.sort((x, y) -> Double.compare(y.getProbBest(), x.getProbBest()));
        return result;
    }

    // Marsaglia and Tsang's method for Beta sampling — pure Java, no extra deps
    private static double sampleBeta(double a, double b, RandomGenerator rng) {
        double x = sampleGamma(a, rng);
        double y = sampleGamma(b, rng);
        return x / (x + y);
    }

    private static double sampleGamma(double shape, RandomGenerator rng) {
        if (shape < 1.0) {
            return sampleGamma(1.0 + shape, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do { x = rng.nextGaussian(); v = 1.0 + c * x; } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1 - 0.0331 * (x * x) * (x * x)) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }

    private static Double toDoubleNullable(Object v) {
        if (v == null) return null;
        if (v instanceof Double d) return d;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
}
