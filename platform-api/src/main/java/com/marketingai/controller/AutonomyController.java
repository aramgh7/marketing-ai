package com.marketingai.controller;

import com.marketingai.service.AutonomyGate;
import com.marketingai.service.AutonomyService;
import com.marketingai.service.OpeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Roadmap 9-3 / arch §15.13: tenant autonomy level with the OPE gate.
 *   GET /api/v1/tenants/me/autonomy  → {level, valid_levels, ope, can_upgrade, reason}
 *   PUT /api/v1/tenants/me/autonomy  {level} → 200 {level} | 409 {error} when the gate refuses
 */
@RestController
@RequestMapping("/tenants/me/autonomy")
@RequiredArgsConstructor
public class AutonomyController {

    private final AutonomyService autonomyService;
    private final OpeService opeService;

    @GetMapping
    public Map<String, Object> get(@RequestAttribute("tenantId") UUID tenantId) {
        String level = autonomyService.getAutonomyLevel(tenantId);
        Optional<Map<String, Object>> ope = opeService.latest(tenantId);
        String verdict = ope.map(r -> (String) r.get("verdict")).orElse(null);
        Long age = ope.map(r -> (Long) r.get("age_days")).orElse(null);
        // "can_upgrade" answers: could the tenant move one step up right now?
        String next = nextLevel(level);
        AutonomyGate.Decision d = next == null
                ? new AutonomyGate.Decision(false, "already at the highest level")
                : AutonomyGate.evaluate(level, next, verdict, age);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("valid_levels", AutonomyGate.LEVELS);
        out.put("ope", ope.map(r -> Map.of(
                "verdict", String.valueOf(r.get("verdict")),
                "created_at", String.valueOf(r.get("created_at")),
                "age_days", r.get("age_days") == null ? -1 : r.get("age_days"))).orElse(null));
        out.put("can_upgrade", d.allowed());
        out.put("reason", d.reason());
        return out;
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> put(@RequestAttribute("tenantId") UUID tenantId,
                                                   @RequestBody Map<String, String> body) {
        String target = body.get("level");
        if (!AutonomyGate.isValid(target)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "level must be one of " + AutonomyGate.LEVELS));
        }
        String current = autonomyService.getAutonomyLevel(tenantId);
        Optional<Map<String, Object>> ope = opeService.latest(tenantId);
        AutonomyGate.Decision d = AutonomyGate.evaluate(current,
                target, ope.map(r -> (String) r.get("verdict")).orElse(null),
                ope.map(r -> (Long) r.get("age_days")).orElse(null));
        if (!d.allowed()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", d.reason(), "level", current));
        }
        autonomyService.setAutonomyLevel(tenantId, target.toUpperCase());
        return ResponseEntity.ok(Map.of("level", target.toUpperCase(), "reason", d.reason()));
    }

    private static String nextLevel(String level) {
        int i = AutonomyGate.rank(level);
        return i + 1 < AutonomyGate.LEVELS.size() ? AutonomyGate.LEVELS.get(i + 1) : null;
    }
}
