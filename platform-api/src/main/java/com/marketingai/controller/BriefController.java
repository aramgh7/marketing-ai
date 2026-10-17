package com.marketingai.controller;

import com.marketingai.dto.brief.BriefRequest;
import com.marketingai.dto.brief.BriefResponse;
import com.marketingai.service.BriefService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/briefs")
@RequiredArgsConstructor
public class BriefController {

    private final BriefService briefService;

    @GetMapping
    public ResponseEntity<List<BriefResponse>> list() {
        return ResponseEntity.ok(briefService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BriefResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(briefService.get(id));
    }

    /**
     * Create a brief and auto-populate atom suggestions from descriptive stats.
     * Target audience, product info, and creative type drive the template.
     */
    @PostMapping
    public ResponseEntity<BriefResponse> create(@Valid @RequestBody BriefRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(briefService.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BriefResponse> update(
            @PathVariable UUID id, @Valid @RequestBody BriefRequest req) {
        return ResponseEntity.ok(briefService.update(id, req));
    }

    /** Re-run atom suggestions from latest descriptive stats. */
    @PostMapping("/{id}/regenerate")
    public ResponseEntity<BriefResponse> regenerate(@PathVariable UUID id) {
        return ResponseEntity.ok(briefService.regenerateSuggestions(id));
    }

    /**
     * Export the brief as Markdown (default) or JSON.
     * ?format=markdown (default) | json
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> export(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "markdown") String format) {
        BriefResponse brief = briefService.export(id);
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok(brief);
        }
        // Default: markdown as plain text
        String md = brief.getMarkdownExport() != null
                ? brief.getMarkdownExport() : "# No content";
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition",
                        "attachment; filename=\"brief-" + id + ".md\"")
                .body(md);
    }

    /** Link two briefs as a paired set (e.g. VIDEO ↔ LANDING for message match). */
    @PostMapping("/{id}/pair")
    public ResponseEntity<BriefResponse> pair(
            @PathVariable UUID id,
            @RequestParam UUID otherId) {
        return ResponseEntity.ok(briefService.pair(id, otherId));
    }

    /** Mark a creative as the result of this brief; sets creatives.creator_id. */
    @PostMapping("/{id}/result")
    public ResponseEntity<BriefResponse> markResult(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        UUID creativeId = UUID.fromString(body.get("creative_id"));
        UUID userId = UUID.fromString(
                SecurityContextHolder.getContext().getAuthentication().getName());
        return ResponseEntity.ok(briefService.markResult(id, creativeId, userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        briefService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Generate a data-driven brief from cluster posteriors + VoC signals (vision §5.5.4).
     * Body: { "cluster_label": "PRICE_SENSITIVE", "audience_segment": "retargeting_engaged" }
     */
    @PostMapping("/generate-data-driven")
    public ResponseEntity<BriefResponse> generateDataDriven(
            @RequestBody Map<String, String> body) {
        String clusterLabel     = body.get("cluster_label");
        String audienceSegment  = body.get("audience_segment");
        if (clusterLabel == null || clusterLabel.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(briefService.generateDataDriven(clusterLabel, audienceSegment));
    }
}
