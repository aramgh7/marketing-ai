package com.marketingai.controller;

import com.marketingai.service.CAPIEmqCheckService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Onboarding readiness checklist + CAPI EMQ pre-check (roadmap 8-2; vision §19.2).
 * GET /api/v1/onboarding/checklist — 7-item go-live checklist
 * GET /api/v1/onboarding/capi-emq-check — CAPI EMQ first-party pre-check
 */
@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
@Slf4j
public class OnboardingController {

    private final JdbcTemplate        jdbc;
    private final CAPIEmqCheckService  emqCheckService;

    @GetMapping("/capi-emq-check")
    public ResponseEntity<Map<String, Object>> capiEmqCheck(
            @RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(emqCheckService.runEmqPreCheck(tenantId));
    }

    @GetMapping("/checklist")
    public ResponseEntity<List<Map<String, Object>>> checklist(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> items = new ArrayList<>();

        int stageCount = count("SELECT COUNT(*) FROM funnel_stages WHERE tenant_id = ?", tenantId);
        items.add(item("funnel_stages", "Funnel stages configured",
                stageCount >= 3, stageCount + " stages found (minimum 3)"));

        int pixelCount = count("""
            SELECT COUNT(*) FROM meta_pixels WHERE tenant_id = ? AND status = 'ACTIVE'
            """, tenantId);
        items.add(item("pixel", "Active Meta pixel configured",
                pixelCount > 0, pixelCount + " active pixel(s)"));

        int capiMapCount = count("SELECT COUNT(*) FROM capi_event_map WHERE tenant_id = ?", tenantId);
        items.add(item("capi_map", "CAPI event mapping defined",
                capiMapCount >= 3, capiMapCount + " events mapped (minimum 3)"));

        int controlCount = count("""
            SELECT COUNT(*) FROM followup_variants
            WHERE tenant_id = ? AND is_control = TRUE AND status = 'ACTIVE'
            """, tenantId);
        items.add(item("followup_control", "Follow-up control arm active",
                controlCount > 0, controlCount + " control arm(s)"));

        int pathCount = count("SELECT COUNT(*) FROM funnel_paths WHERE tenant_id = ?", tenantId);
        items.add(item("funnel_paths", "Funnel paths seeded (CALL_4D minimum)",
                pathCount >= 1, pathCount + " path(s) configured"));

        int ruleCount = count("""
            SELECT COUNT(*) FROM compliance_rulebases
            WHERE (tenant_id = ? OR tenant_id IS NULL) AND is_active = TRUE
            """, tenantId);
        items.add(item("compliance", "Compliance rulebases active",
                ruleCount >= 2, ruleCount + " active rulebase(s)"));

        List<Map<String, Object>> gate = jdbc.queryForList(
                "SELECT status FROM regcf_activation_gate WHERE tenant_id = ?", tenantId);
        String gateStatus = gate.isEmpty() ? "NOT_FOUND" : (String) gate.get(0).get("status");
        items.add(item("regcf_gate", "Reg CF gate acknowledged",
                !gate.isEmpty(), "Status: " + gateStatus + " (FROZEN is correct for 506(c) launch)"));

        long passed = items.stream().filter(i -> Boolean.TRUE.equals(i.get("passed"))).count();
        boolean ready = passed == items.size();

        items.add(0, Map.of("_summary", Map.of(
                "ready_to_launch", ready,
                "passed",          passed,
                "total",           items.size()
        )));

        return ResponseEntity.ok(items);
    }

    private int count(String sql, UUID tenantId) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, tenantId);
            return n != null ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Map<String, Object> item(String key, String label, boolean passed, String detail) {
        return Map.of("key", key, "label", label, "passed", passed, "detail", detail);
    }
}
