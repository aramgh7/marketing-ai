package com.marketingai.controller;

import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.service.LandingSetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/generate-set")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> generateSet(@RequestBody Map<String, Object> body) {
        return sets.generateSet(body);
    }

    @GetMapping("/sets")
    public List<Map<String, Object>> listSets() {
        return sets.listSets();
    }

    @PostMapping("/{id}/reject")
    public VariantResponse reject(@PathVariable UUID id,
                                  @RequestAttribute(value = "userId", required = false) UUID userId,
                                  @RequestBody Map<String, String> body) {
        return sets.reject(id, userId, body.get("reason"), body.get("note"));
    }
}
