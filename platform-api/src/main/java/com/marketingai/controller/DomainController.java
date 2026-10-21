package com.marketingai.controller;

import com.marketingai.service.TenantDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Roadmap Phase 10: client domains for landings (CNAME to our edge or self-hosted runner). */
@RestController
@RequestMapping("/domains")
@RequiredArgsConstructor
public class DomainController {

    private final TenantDomainService domains;

    @GetMapping
    public List<Map<String, Object>> list() {
        return domains.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        return domains.create(body.get("host"), body.get("kind"), body.get("hosting"), body.get("default_slug"));
    }

    @PostMapping("/{id}/verify")
    public Map<String, Object> verify(@PathVariable UUID id) {
        return domains.verify(id);
    }

    @PostMapping("/{id}/disable")
    public Map<String, Object> disable(@PathVariable UUID id) {
        return domains.disable(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        domains.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/runner-key")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> runnerKey(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return domains.issueRunnerKey(id, body == null ? null : body.get("label"));
    }
}
