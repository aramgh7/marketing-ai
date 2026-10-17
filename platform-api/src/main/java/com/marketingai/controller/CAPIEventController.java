package com.marketingai.controller;

import com.marketingai.entity.CAPIEventLog;
import com.marketingai.repository.CAPIEventLogRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/capi-events")
@RequiredArgsConstructor
public class CAPIEventController {

    private final CAPIEventLogRepository capiRepo;

    /** Recent CAPI events for this tenant — used for EMQ monitoring in the dashboard. */
    @GetMapping
    public List<CAPIEventLog> list(
            @RequestParam(defaultValue = "50") int limit) {
        int cap = Math.min(limit, 200);
        return capiRepo.findByTenantIdOrderByCreatedAtDesc(
                currentTenant(), PageRequest.of(0, cap));
    }

    /** Quick summary: counts by status. */
    @GetMapping("/summary")
    public Map<String, Long> summary() {
        UUID tid = currentTenant();
        return Map.of(
                "sent",    capiRepo.countByTenantIdAndStatus(tid, "SENT"),
                "pending", capiRepo.countByTenantIdAndStatus(tid, "PENDING"),
                "failed",  capiRepo.countByTenantIdAndStatus(tid, "FAILED"),
                "skipped", capiRepo.countByTenantIdAndStatus(tid, "SKIPPED")
        );
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
