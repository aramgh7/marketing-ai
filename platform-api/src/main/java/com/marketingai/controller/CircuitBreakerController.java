package com.marketingai.controller;

import com.marketingai.entity.CircuitBreakerEvent;
import com.marketingai.repository.CircuitBreakerEventRepository;
import com.marketingai.security.TenantContext;
import com.marketingai.service.CircuitBreakerService;
import com.marketingai.service.RecoveryDiagnosisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Circuit Breaker visibility and resolution.
 * GET /circuit-breaker — list active events
 * POST /circuit-breaker/{id}/resolve — marketer resolves L2/L3
 * POST /circuit-breaker/check — manual trigger (for testing / on-demand)
 */
@RestController
@RequestMapping("/circuit-breaker")
@RequiredArgsConstructor
public class CircuitBreakerController {

    private final CircuitBreakerService cbService;
    private final CircuitBreakerEventRepository cbRepo;
    private final RecoveryDiagnosisService recoveryDiagnosisService;

    @GetMapping
    public List<CircuitBreakerEvent> listActive() {
        return cbRepo.findByTenantIdAndResolvedAtIsNull(currentTenant());
    }

    @GetMapping("/history")
    public List<CircuitBreakerEvent> history() {
        return cbRepo.findByTenantIdAndCreatedAtAfter(currentTenant(),
                java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS));
    }

    @PostMapping("/{id}/resolve")
    public CircuitBreakerEvent resolve(@PathVariable UUID id,
                                       @RequestParam(defaultValue = "marketer") String resolvedBy) {
        cbService.resolveEvent(currentTenant(), id, resolvedBy);
        return cbRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/check")
    public String manualCheck() {
        cbService.checkAll(currentTenant());
        return "OK";
    }

    @GetMapping("/{id}/diagnosis")
    public Map<String, Object> getDiagnosis(@PathVariable UUID id) {
        return recoveryDiagnosisService.diagnose(currentTenant(), id);
    }

    @PostMapping("/{id}/confirm-cause")
    public Map<String, String> confirmCause(@PathVariable UUID id,
                                             @RequestParam String cause) {
        recoveryDiagnosisService.confirmCause(currentTenant(), id, cause);
        return Map.of("status", "confirmed", "cause", cause);
    }

    @PostMapping("/resume-batch")
    public Map<String, Object> resumeBatch(@RequestBody List<UUID> ids) {
        int resumed = recoveryDiagnosisService.resumeBatch(currentTenant(), ids);
        return Map.of("resumed", resumed, "requested", ids.size());
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
