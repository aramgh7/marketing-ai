package com.marketingai.controller;

import com.marketingai.service.MetaApiAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final MetaApiAuditService auditService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAuditLog(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(auditService.getAuditLog(tenantId, Math.min(limit, 500)));
    }
}
