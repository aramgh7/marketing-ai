package com.marketingai.controller;

import com.marketingai.dto.budget.*;
import com.marketingai.security.TenantContext;
import com.marketingai.service.BudgetGuardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/budget-guards")
@RequiredArgsConstructor
public class BudgetGuardController {

    private final BudgetGuardService service;

    @GetMapping
    public List<BudgetGuardResponse> list() {
        return service.list(currentTenant());
    }

    @PostMapping
    public ResponseEntity<BudgetGuardResponse> create(
            @Valid @RequestBody CreateGuardRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(currentTenant(), req));
    }

    @GetMapping("/{id}")
    public BudgetGuardResponse get(@PathVariable UUID id) {
        return service.get(currentTenant(), id);
    }

    @PutMapping("/{id}")
    public BudgetGuardResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateGuardRequest req) {
        return service.update(currentTenant(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(currentTenant(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Manually sync current spend from Meta Ads Manager.
     * Phase 3: this will be replaced by auto-sync from Meta Marketing API.
     */
    @PatchMapping("/{id}/spend")
    public BudgetGuardResponse updateSpend(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateSpendRequest req) {
        return service.updateSpend(currentTenant(), id, req);
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
