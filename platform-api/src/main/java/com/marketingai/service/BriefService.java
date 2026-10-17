package com.marketingai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.dto.analytics.AtomPerformanceRow;
import com.marketingai.dto.analytics.OverviewResponse;
import com.marketingai.dto.brief.BriefRequest;
import com.marketingai.dto.brief.BriefResponse;
import com.marketingai.entity.CreatorBrief;
import com.marketingai.repository.CreatorBriefRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class BriefService {

    private final CreatorBriefRepository briefRepository;
    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    static final String STRUCTURE_TEMPLATE =
            "Hook (3s) → Problem (10s) → Solution (15s) → Proof (15s) → CTA (5s)";

    // ─── List ────────────────────────────────────────────────────────────────

    public List<BriefResponse> list() {
        return briefRepository.findByTenantIdOrderByCreatedAtDesc(tenantId())
                .stream().map(b -> toResponse(b, false)).toList();
    }

    // ─── Get ─────────────────────────────────────────────────────────────────

    public BriefResponse get(UUID id) {
        return toResponse(findOrThrow(id), false);
    }

    // ─── Create (with auto-generated atom suggestions) ────────────────────────

    @Transactional
    public BriefResponse create(BriefRequest req) {
        CreatorBrief brief = new CreatorBrief();
        brief.setTenantId(tenantId());
        applyRequest(brief, req);

        // Pull atom suggestions from descriptive stats (last 90 days)
        populateAtomSuggestions(brief, req.getCreativeType());

        return toResponse(briefRepository.save(brief), false);
    }

    // ─── Update (increments version + snapshots) ─────────────────────────────

    @Transactional
    public BriefResponse update(UUID id, BriefRequest req) {
        CreatorBrief brief = findOrThrow(id);
        snapshotVersion(brief);
        brief.setVersion(brief.getVersion() + 1);
        applyRequest(brief, req);
        return toResponse(briefRepository.save(brief), false);
    }

    // ─── Pair two briefs (video ↔ landing) ───────────────────────────────────

    @Transactional
    public BriefResponse pair(UUID id, UUID otherId) {
        CreatorBrief a = findOrThrow(id);
        CreatorBrief b = briefRepository.findByIdAndTenantId(otherId, tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Partner brief not found"));
        if (a.getBriefType() != null && b.getBriefType() != null
                && a.getBriefType().equals(b.getBriefType())
                && !"GENERAL".equals(a.getBriefType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cannot pair two briefs of the same type (" + a.getBriefType() + ")");
        }
        a.setPairedBriefId(otherId);
        b.setPairedBriefId(id);
        briefRepository.save(a);
        briefRepository.save(b);
        return toResponse(a, false);
    }

    // ─── Mark result creative ─────────────────────────────────────────────────

    @Transactional
    public BriefResponse markResult(UUID id, UUID creativeId, UUID userId) {
        CreatorBrief brief = findOrThrow(id);
        brief.setResultCreativeId(creativeId);
        jdbc.update(
            "UPDATE creatives SET creator_id = ? WHERE id = ? AND tenant_id = ?",
            userId, creativeId, brief.getTenantId());
        return toResponse(briefRepository.save(brief), false);
    }

    // ─── Regenerate atom suggestions ─────────────────────────────────────────

    @Transactional
    public BriefResponse regenerateSuggestions(UUID id) {
        CreatorBrief brief = findOrThrow(id);
        populateAtomSuggestions(brief, brief.getCreativeType());
        return toResponse(briefRepository.save(brief), false);
    }

    // ─── Export as Markdown ───────────────────────────────────────────────────

    public BriefResponse export(UUID id) {
        CreatorBrief brief = findOrThrow(id);
        return toResponse(brief, true);
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    @Transactional
    public void delete(UUID id) {
        briefRepository.delete(findOrThrow(id));
    }

    // ─── Data-driven brief by cluster/audience (5-8, vision §5.5.4) ─────────

    @Transactional
    public BriefResponse generateDataDriven(String clusterLabel, String audienceSegment) {
        UUID tid = tenantId();

        // Load top posteriors for active variants; filter by cluster via sessions
        // thompson_arms has no cluster_label — we find variants shown to this cluster via events
        List<Map<String, Object>> arms = jdbc.queryForList("""
                SELECT DISTINCT ta.variant_id, ta.alpha, ta.beta, v.name AS variant_name
                FROM thompson_arms ta
                JOIN variants v ON v.id = ta.variant_id
                WHERE v.tenant_id = ?
                  AND v.status = 'ACTIVE'
                  AND EXISTS (
                      SELECT 1 FROM sessions s
                      WHERE s.variant_id = v.id
                        AND s.cluster_label = ?
                        AND s.tenant_id = ?
                  )
                ORDER BY (ta.alpha / NULLIF(ta.alpha + ta.beta, 0)) DESC
                LIMIT 10
                """, tid, clusterLabel, tid);

        // Load VoC signals for this cluster
        List<Map<String, Object>> vocSignals = jdbc.queryForList("""
                SELECT signal_text, sentiment, source
                FROM brief_voc_signals bvs
                JOIN creator_briefs cb ON cb.id = bvs.brief_id
                WHERE cb.tenant_id = ?
                ORDER BY bvs.created_at DESC
                LIMIT 20
                """, tid);

        // Load niche benchmark for context
        List<Map<String, Object>> benchmarks = jdbc.queryForList("""
                SELECT metric_name, niche_mean, niche_std, n_tenants
                FROM niche_benchmarks
                WHERE niche_label = ?
                LIMIT 5
                """, clusterLabel);

        // Build VoC section
        StringBuilder vocSection = new StringBuilder();
        if (!vocSignals.isEmpty()) {
            vocSection.append("## Voice of Customer (").append(clusterLabel).append(")\n");
            vocSignals.stream().limit(5).forEach(s ->
                vocSection.append("- [").append(s.get("sentiment")).append("] ")
                          .append(s.get("signal_text")).append("\n"));
        }

        // Build top performing variant note
        StringBuilder notes = new StringBuilder();
        notes.append("Cluster: ").append(clusterLabel);
        if (audienceSegment != null && !audienceSegment.isBlank()) {
            notes.append(", Segment: ").append(audienceSegment);
        }
        if (!arms.isEmpty()) {
            Map<String, Object> top = arms.get(0);
            double alpha = ((Number) top.get("alpha")).doubleValue();
            double beta  = ((Number) top.get("beta")).doubleValue();
            double cvr   = alpha / (alpha + beta);
            notes.append(String.format(". Top variant '%s': CVR=%.2f%%",
                    top.get("variant_name"), cvr * 100));
        }
        if (!benchmarks.isEmpty()) {
            notes.append(". Niche benchmarks: ");
            benchmarks.forEach(b ->
                notes.append(b.get("metric_name")).append("=").append(b.get("niche_mean")).append(" "));
        }

        CreatorBrief brief = new CreatorBrief();
        brief.setTenantId(tid);
        brief.setTitle("Data-Driven Brief — " + clusterLabel
                + (audienceSegment != null ? " / " + audienceSegment : ""));
        brief.setCreativeType("VIDEO");
        brief.setBriefType("VIDEO");
        brief.setTargetAudience(clusterLabel);
        brief.setNotes(notes.toString());
        brief.setStructureTemplate(STRUCTURE_TEMPLATE);
        brief.setVocSection(vocSection.length() > 0 ? vocSection.toString() : null);

        try {
            String meta = objectMapper.writeValueAsString(Map.of(
                    "cluster_label",    clusterLabel,
                    "audience_segment", audienceSegment != null ? audienceSegment : "",
                    "arms_loaded",      arms.size(),
                    "voc_signals",      vocSignals.size()
            ));
            brief.setMeta(meta);
        } catch (Exception ignored) {}

        populateAtomSuggestions(brief, "VIDEO");
        return toResponse(briefRepository.save(brief), false);
    }

    // ─── Block F §3: Enrich with Voice-of-Customer signals ───────────────────

    /**
     * Attach VoC signals (reviews, support tickets, survey responses) to a brief.
     * Stored in brief_voc_signals (V82). Each signal includes source + sentiment
     * so writers can tailor copy to real customer language (vision §8.2).
     */
    @Transactional
    public Map<String, Object> enrichWithVoC(UUID briefId, List<Map<String, Object>> signals) {
        CreatorBrief brief = findOrThrow(briefId);
        UUID tenantId = brief.getTenantId();
        int inserted = 0;

        for (Map<String, Object> signal : signals) {
            String source = String.valueOf(signal.getOrDefault("source", "UNKNOWN")).toUpperCase();
            String text   = String.valueOf(signal.getOrDefault("signal_text", ""));
            String sentiment = signal.containsKey("sentiment")
                    ? String.valueOf(signal.get("sentiment")).toUpperCase() : null;

            if (text.isBlank()) continue;

            jdbc.update("""
                INSERT INTO brief_voc_signals (tenant_id, brief_id, source, signal_text, sentiment)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, briefId, source, text, sentiment);
            inserted++;
        }

        log.info("Enriched brief {} with {} VoC signals (tenant {})", briefId, inserted, tenantId);
        return Map.of(
            "brief_id",      briefId.toString(),
            "signals_added", inserted,
            "status",        "OK"
        );
    }

    /**
     * Retrieve VoC signals attached to a brief, ordered most recent first.
     */
    public List<Map<String, Object>> getVoC(UUID briefId) {
        findOrThrow(briefId); // ensures tenant isolation via RLS
        return jdbc.queryForList("""
            SELECT id, source, signal_text, sentiment, created_at
            FROM brief_voc_signals
            WHERE brief_id = ?
            ORDER BY created_at DESC
            """, briefId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void snapshotVersion(CreatorBrief brief) {
        try {
            java.util.Map<String, Object> snap = new java.util.LinkedHashMap<>();
            snap.put("title",        brief.getTitle());
            snap.put("creativeType", brief.getCreativeType());
            snap.put("targetAudience", brief.getTargetAudience());
            snap.put("productInfo",  brief.getProductInfo());
            snap.put("notes",        brief.getNotes());
            snap.put("status",       brief.getStatus());
            snap.put("briefType",    brief.getBriefType());
            String snapJson = objectMapper.writeValueAsString(snap);
            jdbc.update("""
                INSERT INTO brief_versions (brief_id, tenant_id, version, snapshot)
                VALUES (?, ?, ?, ?::jsonb)
                """, brief.getId(), brief.getTenantId(), brief.getVersion(), snapJson);
        } catch (Exception e) {
            log.warn("Brief snapshot failed: {}", e.getMessage());
        }
    }

    private void applyRequest(CreatorBrief brief, BriefRequest req) {
        brief.setTitle(req.getTitle());
        brief.setCreativeType(req.getCreativeType() != null
                ? req.getCreativeType().toUpperCase() : null);
        brief.setTargetAudience(req.getTargetAudience());
        brief.setProductInfo(req.getProductInfo());
        brief.setNotes(req.getNotes());
        if (req.getStatus() != null) brief.setStatus(req.getStatus().toUpperCase());
        if (req.getBriefType() != null) brief.setBriefType(req.getBriefType().toUpperCase());
        brief.setStructureTemplate(STRUCTURE_TEMPLATE);
    }

    private void populateAtomSuggestions(CreatorBrief brief, String creativeType) {
        try {
            List<AtomPerformanceRow> atoms = analyticsService.atomPerformance(90, null);
            OverviewResponse overview = analyticsService.overview(90);

            // Top performers: at least 2 creatives, CVR not null, top 10 by CVR
            List<Map<String, Object>> recommended = atoms.stream()
                    .filter(a -> a.getCreativeCount() >= 2 && a.getConversionRate() != null)
                    .sorted((a, b) -> Double.compare(
                            b.getConversionRate(), a.getConversionRate()))
                    .limit(10)
                    .map(a -> Map.<String, Object>of(
                            "atomType", a.getAtomType(),
                            "atomValue", a.getAtomValue(),
                            "cvr", String.format("%.2f%%", a.getConversionRate() * 100),
                            "creativeCount", a.getCreativeCount(),
                            "rationale", "Top CVR performer in this account"
                    ))
                    .toList();

            // Avoid: have sessions but CVR below average, at least 2 creatives
            double avgCvr = overview.getConversionRate() != null
                    ? overview.getConversionRate() : 0.01;

            List<Map<String, Object>> avoid = atoms.stream()
                    .filter(a -> a.getCreativeCount() >= 2
                            && a.getConversionRate() != null
                            && a.getTotalSessions() >= 50
                            && a.getConversionRate() < avgCvr * 0.5)
                    .sorted((a, b) -> Double.compare(
                            a.getConversionRate(), b.getConversionRate()))
                    .limit(5)
                    .map(a -> Map.<String, Object>of(
                            "atomType", a.getAtomType(),
                            "atomValue", a.getAtomValue(),
                            "cvr", String.format("%.2f%%", a.getConversionRate() * 100),
                            "creativeCount", a.getCreativeCount(),
                            "rationale", "Below-average CVR — avoid or test alternatives"
                    ))
                    .toList();

            // Benchmarks
            double topCvr = recommended.isEmpty() ? 0.0
                    : atoms.stream()
                            .filter(a -> a.getConversionRate() != null)
                            .mapToDouble(AtomPerformanceRow::getConversionRate)
                            .max().orElse(0.0);

            String topHook = atoms.stream()
                    .filter(a -> "HOOK".equals(a.getAtomType()) && a.getConversionRate() != null)
                    .max((a, b) -> Double.compare(a.getConversionRate(), b.getConversionRate()))
                    .map(AtomPerformanceRow::getAtomValue)
                    .orElse("n/a");

            Map<String, Object> benchmarks = new java.util.LinkedHashMap<>();
            benchmarks.put("cvrAvg", avgCvr != 0.01
                    ? String.format("%.2f%%", avgCvr * 100) : "n/a");
            benchmarks.put("cvrTopAtom", String.format("%.2f%%", topCvr * 100));
            benchmarks.put("topHook", topHook);
            benchmarks.put("dataPeriodDays", 90);
            benchmarks.put("note",
                    "Phase 1: descriptive stats from first-party events. " +
                    "Causal benchmarks available in Phase 3.");

            brief.setRecommendedAtoms(objectMapper.writeValueAsString(recommended));
            brief.setAvoidAtoms(objectMapper.writeValueAsString(avoid));
            brief.setBenchmarks(objectMapper.writeValueAsString(benchmarks));

        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize atom suggestions: {}", e.getMessage());
        }
    }

    private BriefResponse toResponse(CreatorBrief b, boolean withMarkdown) {
        return BriefResponse.builder()
                .id(b.getId())
                .tenantId(b.getTenantId())
                .title(b.getTitle())
                .creativeType(b.getCreativeType())
                .targetAudience(b.getTargetAudience())
                .productInfo(b.getProductInfo())
                .recommendedAtoms(b.getRecommendedAtoms())
                .avoidAtoms(b.getAvoidAtoms())
                .structureTemplate(b.getStructureTemplate())
                .benchmarks(b.getBenchmarks())
                .notes(b.getNotes())
                .status(b.getStatus())
                .version(b.getVersion())
                .briefType(b.getBriefType())
                .pairedBriefId(b.getPairedBriefId())
                .resultCreativeId(b.getResultCreativeId())
                .markdownExport(withMarkdown ? buildMarkdown(b) : null)
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }

    private String buildMarkdown(CreatorBrief b) {
        var sb = new StringBuilder();
        sb.append("# Creator Brief: ").append(b.getTitle()).append("\n\n");
        sb.append("> Phase 1 template brief. Data-driven version available in Phase 3.\n\n");
        sb.append("## 🎯 Creative Type\n").append(b.getCreativeType()).append("\n\n");

        if (b.getTargetAudience() != null && !b.getTargetAudience().isBlank()) {
            sb.append("## 👥 Target Audience\n").append(b.getTargetAudience()).append("\n\n");
        }
        if (b.getProductInfo() != null && !b.getProductInfo().isBlank()) {
            sb.append("## 📦 Product Info\n").append(b.getProductInfo()).append("\n\n");
        }

        sb.append("## 📋 Structure Template\n")
                .append(b.getStructureTemplate() != null
                        ? b.getStructureTemplate() : STRUCTURE_TEMPLATE)
                .append("\n\n");

        appendAtomSection(sb, "✅ Recommended Atoms (Top CVR)", b.getRecommendedAtoms());
        appendAtomSection(sb, "❌ Avoid (Low CVR)", b.getAvoidAtoms());

        sb.append("## 📊 Benchmarks\n");
        sb.append(b.getBenchmarks() != null ? b.getBenchmarks() : "No data yet.").append("\n\n");

        appendVocSection(sb, b.getTenantId());

        if (b.getNotes() != null && !b.getNotes().isBlank()) {
            sb.append("## 📝 Notes\n").append(b.getNotes()).append("\n\n");
        }

        sb.append("---\n_Generated by Marketing AI Platform — Phase 1 descriptive analytics_\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendAtomSection(StringBuilder sb, String heading, String jsonArray) {
        sb.append("## ").append(heading).append("\n");
        if (jsonArray == null || jsonArray.equals("[]")) {
            sb.append("_No data yet — upload and analyze more creatives._\n\n");
            return;
        }
        try {
            List<Map<String, Object>> atoms =
                    objectMapper.readValue(jsonArray, List.class);
            for (var a : atoms) {
                sb.append(String.format("- **%s**: `%s` — CVR %s (%s creatives)\n",
                        a.get("atomType"), a.get("atomValue"),
                        a.get("cvr"), a.get("creativeCount")));
            }
        } catch (Exception e) {
            sb.append("_Could not parse atom data._\n");
        }
        sb.append("\n");
    }

    private void appendVocSection(StringBuilder sb, UUID tenantId) {
        sb.append("## Возражения аудитории (из VoC)\n");
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category, objection_text, count "
                + "FROM voc_objections WHERE tenant_id = ? "
                + "ORDER BY count DESC LIMIT 5",
                tenantId);
            if (rows.isEmpty()) {
                sb.append("_Возражения не загружены. Используйте импорт VoC для анализа._\n\n");
                return;
            }
            for (var row : rows) {
                sb.append(String.format("- **%s**: %s (%s упоминаний)\n",
                    row.get("category"), row.get("objection_text"), row.get("count")));
            }
            sb.append("\n");
        } catch (Exception e) {
            log.warn("Failed to fetch VoC objections for brief: {}", e.getMessage());
            sb.append("_Данные VoC недоступны._\n\n");
        }
    }

    private CreatorBrief findOrThrow(UUID id) {
        return briefRepository.findByIdAndTenantId(id, tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Brief not found"));
    }

    private UUID tenantId() {
        return UUID.fromString(TenantContext.get());
    }
}
