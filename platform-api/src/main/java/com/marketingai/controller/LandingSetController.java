package com.marketingai.controller;

import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.service.LandingSetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Roadmap Phase 10: generate N landing variants from one brief, list sets, reject a variant with a reason. */
@RestController
@RequestMapping("/landings")
@RequiredArgsConstructor
public class LandingSetController {

    private final LandingSetService sets;
    private final com.marketingai.service.MlServiceClient mlClient;

    @PostMapping("/generate-set")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> generateSet(@RequestBody Map<String, Object> body) {
        return sets.generateSet(body);
    }

    @GetMapping("/sets")
    public List<Map<String, Object>> listSets() {
        return sets.listSets();
    }

    /** Roadmap Phase 11-5: pairs "creative → landing variant" ranked by ML (/decide/pairs). */
    @GetMapping("/pairs")
    public Object pairs(@RequestParam(value = "days", required = false, defaultValue = "30") int days) {
        String raw = com.marketingai.security.TenantContext.get();
        if (raw == null) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        Object r = mlClient.get("/decide/pairs?tenant_id=" + raw + "&days=" + days, Object.class);
        return r == null ? Map.of("pairs", List.of()) : r;
    }

    /** Phase 14, §19.5: auto-fill pipeline — template + product facts + asset library → variants. */
    @PostMapping("/generate-visual")
    public ResponseEntity<Map<String, Object>> generateVisual(@RequestBody Map<String, Object> body) {
        UUID creativeId = UUID.fromString(String.valueOf(body.get("creative_id")));
        UUID templateId = UUID.fromString(String.valueOf(body.get("template_id")));
        return ResponseEntity.accepted().body(sets.generateVisualSet(creativeId, templateId, body));
    }

    @PostMapping("/{id}/reject")
    public VariantResponse reject(@PathVariable UUID id,
                                  @RequestAttribute(value = "userId", required = false) UUID userId,
                                  @RequestBody Map<String, String> body) {
        return sets.reject(id, userId, body.get("reason"), body.get("note"));
    }
}
