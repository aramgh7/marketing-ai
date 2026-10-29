package com.marketingai.controller;

import com.marketingai.dto.analytics.*;
import com.marketingai.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /** Tenant overview: sessions, conversions, revenue, creative counts. */
    @GetMapping("/overview")
    public ResponseEntity<OverviewResponse> overview(
            @RequestParam(defaultValue = "30") int days) {
        days = clampDays(days);
        return ResponseEntity.ok(analyticsService.overview(days));
    }

    /** Per-creative performance table. Meta metrics (spend, ROAS) are null until Phase 3. */
    @GetMapping("/creatives")
    public ResponseEntity<Map<String, Object>> creatives(
            @RequestParam(defaultValue = "30") int days) {
        days = clampDays(days);
        List<CreativePerformanceRow> data = analyticsService.creativePerformance(days);
        return ResponseEntity.ok(Map.of(
                "data", data,
                "disclaimer", AnalyticsService.DISCLAIMER
        ));
    }

    /**
     * Atom-level breakdown: for each atom_type × atom_value, shows creative count,
     * sessions, conversions, and conversion rate derived from first-party events.
     * Optional filter: ?atomType=HOOK
     */
    @GetMapping("/atoms")
    public ResponseEntity<Map<String, Object>> atoms(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) String atomType) {
        days = clampDays(days);
        List<AtomPerformanceRow> data = analyticsService.atomPerformance(days, atomType);
        // Phase 14, §19.6/§19.8: include visual_atoms alongside text atoms.
        // Full visual atom coefficients computed by POST /glm/visual-atoms in the ML service.
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("data", data);
        response.put("disclaimer", AnalyticsService.DISCLAIMER);
        response.put("visual_atoms_endpoint", "/glm/visual-atoms");
        return ResponseEntity.ok(response);
    }

    /**
     * Funnel drop-off analysis + §3.2 diagnostic card.
     * Phase 1 symptoms: HIGH_BOUNCE, HIGH_FORM_ABANDON, LOW_CVR, WEAK_CTA.
     * Meta-dependent symptoms (LOW_HOOK_RATE, LOW_CTR) included as placeholders.
     */
    @GetMapping("/funnel")
    public ResponseEntity<FunnelResponse> funnel(
            @RequestParam(defaultValue = "30") int days) {
        days = clampDays(days);
        return ResponseEntity.ok(analyticsService.funnel(days));
    }

    /**
     * Bayesian A/B dashboard: per-variant posterior CVR, P(best), and observed stats.
     * Winner recommendation: arm with highest probability of being best.
     */
    @GetMapping("/ab")
    public ResponseEntity<Map<String, Object>> abDashboard(
            @RequestParam(defaultValue = "30") int days) {
        days = clampDays(days);
        List<AbVariantRow> data = analyticsService.abDashboard(days);
        return ResponseEntity.ok(Map.of(
                "data", data,
                "disclaimer", AnalyticsService.DISCLAIMER
        ));
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, 365));
    }
}
