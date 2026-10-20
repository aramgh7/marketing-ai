package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.service.OpeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Off-Policy Evaluation gate (3-18, roadmap 9-3).
 * GET  /api/v1/ope           — latest OPE run for the current tenant (+ age_days)
 * POST /api/v1/ope/evaluate  — trigger OPE now, save the run
 */
@RestController
@RequestMapping("/ope")
@RequiredArgsConstructor
public class OpeController {

    private final OpeService opeService;

    @GetMapping
    public Map<String, Object> getLatestBenchmark() {
        return opeService.latest(currentTenant())
                .orElse(Map.of("verdict", "INSUFFICIENT_DATA", "message", "No OPE runs yet."));
    }

    @PostMapping("/evaluate")
    public Map<String, Object> evaluate() {
        return opeService.evaluate(currentTenant());
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
