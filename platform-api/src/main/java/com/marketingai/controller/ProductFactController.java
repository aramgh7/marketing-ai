package com.marketingai.controller;

import com.marketingai.entity.ProductFact;
import com.marketingai.repository.ProductFactRepository;
import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/product-facts")
@RequiredArgsConstructor
public class ProductFactController {

    private final ProductFactRepository repo;

    @GetMapping
    public ResponseEntity<List<ProductFact>> list() {
        return ResponseEntity.ok(repo.findByTenantIdOrderByCreatedAtDesc(UUID.fromString(TenantContext.get())));
    }

    @PostMapping
    public ResponseEntity<ProductFact> create(@RequestBody Map<String, String> body) {
        ProductFact fact = new ProductFact();
        fact.setTenantId(UUID.fromString(TenantContext.get()));
        fact.setFact(body.get("fact"));
        fact.setSource(body.get("source"));
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.save(fact));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID tenantId = UUID.fromString(TenantContext.get());
        ProductFact fact = repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        repo.delete(fact);
        return ResponseEntity.noContent().build();
    }
}
