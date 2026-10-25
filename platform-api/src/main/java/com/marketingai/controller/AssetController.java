package com.marketingai.controller;

import com.marketingai.service.AssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String l1,
            @RequestParam(required = false) String l2,
            @RequestParam(required = false) String l3) {
        return assetService.list(l1, l2, l3);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        return assetService.register(body);
    }

    @PutMapping("/{id}/tags")
    public Map<String, Object> saveTags(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return assetService.saveTags(id, body);
    }

    @PostMapping("/{id}/auto-tag-result")
    public Map<String, Object> storeAutoTag(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return assetService.storeAutoTagSuggestions(id, body);
    }

    @GetMapping("/{id}/placements")
    public List<Map<String, Object>> placements(@PathVariable UUID id) {
        return assetService.placements(id);
    }

    @GetMapping("/tag-values")
    public Map<String, Object> tagValues() {
        return Map.of("l1", AssetService.L1_VALUES, "l2", AssetService.L2_VALUES, "l3", AssetService.L3_VALUES);
    }
}
