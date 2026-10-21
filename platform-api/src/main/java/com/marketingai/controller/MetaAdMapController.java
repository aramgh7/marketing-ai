package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import com.marketingai.service.CreativePublishService;
import com.marketingai.service.LinkageHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Roadmap Phase 11-4: the Meta ad ↔ creative map, manual linking of hand-made ads, drift acknowledgement,
 * linkage health.
 */
@RestController
@RequestMapping("/meta")
@RequiredArgsConstructor
public class MetaAdMapController {

    private final CreativePublishService publish;
    private final LinkageHealthService linkage;

    @GetMapping("/ad-map")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return publish.listMap(status);
    }

    @PostMapping("/ad-map/{id}/link")
    public Map<String, Object> link(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Object cid = body.get("creative_id");
        if (cid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creative_id required");
        return publish.link(id, UUID.fromString(String.valueOf(cid)));
    }

    @PostMapping("/ad-map/{id}/acknowledge-drift")
    public Map<String, Object> acknowledgeDrift(@PathVariable UUID id) {
        return publish.acknowledgeDrift(id);
    }

    @GetMapping("/linkage-health")
    public Map<String, Object> linkageHealth() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        LinkageHealthService.Health h = linkage.check(UUID.fromString(raw));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("healthy", h.healthy());
        out.put("active_ads", h.activeAds());
        out.put("unmanaged_count", h.unmanaged());
        out.put("missing_tags_count", h.missingTags());
        out.put("drift_count", h.drift());
        out.put("summary", h.summary());
        return out;
    }
}
