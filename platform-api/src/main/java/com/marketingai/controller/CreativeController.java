package com.marketingai.controller;

import com.marketingai.dto.creative.CreativeAtomResponse;
import com.marketingai.dto.creative.CreativeResponse;
import com.marketingai.dto.creative.UpdateAtomRequest;
import com.marketingai.service.CreativeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/creatives")
@RequiredArgsConstructor
public class CreativeController {

    private final CreativeService creativeService;

    /** GET /creatives?type=VIDEO */
    @GetMapping
    public List<CreativeResponse> list(@RequestParam(required = false) String type) {
        return creativeService.list(type);
    }

    /** GET /creatives/{id} — includes atoms */
    @GetMapping("/{id}")
    public CreativeResponse get(@PathVariable UUID id) {
        return creativeService.get(id);
    }

    /**
     * POST /creatives — multipart/form-data
     * Fields: type (required), title, externalId, metaAdSetId, fileUrl, file
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreativeResponse create(
            @RequestParam("type") String type,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "externalId", required = false) String externalId,
            @RequestParam(value = "metaAdSetId", required = false) String metaAdSetId,
            @RequestParam(value = "fileUrl", required = false) String fileUrl,
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return creativeService.create(type, title, externalId, metaAdSetId, fileUrl, file);
    }

    /**
     * PUT /creatives/{id} — update metadata (title, externalId, transcript, etc.)
     * Accepts JSON body.
     */
    @PutMapping("/{id}")
    public CreativeResponse update(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        return creativeService.update(
                id,
                (String) body.get("title"),
                (String) body.get("externalId"),
                (String) body.get("metaAdSetId"),
                (String) body.get("transcript"),
                body.get("durationSec") != null ? ((Number) body.get("durationSec")).floatValue() : null
        );
    }

    /** POST /creatives/{id}/analyze — trigger Creative DNA extraction */
    @PostMapping("/{id}/analyze")
    public ResponseEntity<Map<String, String>> analyze(@PathVariable UUID id) {
        String jobId = creativeService.triggerAnalysis(id);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued"));
    }

    /** PUT /creatives/{id}/anchor — toggle anchor flag */
    @PutMapping("/{id}/anchor")
    public CreativeResponse toggleAnchor(@PathVariable UUID id) {
        return creativeService.toggleAnchor(id);
    }

    /** DELETE /creatives/{id} — archive (soft delete) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        creativeService.archive(id);
    }

    // ─── Atom endpoints ──────────────────────────────────────────────────────

    /** POST /creatives/{id}/atoms — add atom manually */
    @PostMapping("/{id}/atoms")
    @ResponseStatus(HttpStatus.CREATED)
    public CreativeAtomResponse addAtom(@PathVariable UUID id, @Valid @RequestBody UpdateAtomRequest req) {
        return creativeService.addAtom(id, req);
    }

    /** PUT /creatives/{id}/atoms/{atomId} — update atom */
    @PutMapping("/{id}/atoms/{atomId}")
    public CreativeAtomResponse updateAtom(@PathVariable UUID id,
                                           @PathVariable UUID atomId,
                                           @Valid @RequestBody UpdateAtomRequest req) {
        return creativeService.updateAtom(id, atomId, req);
    }

    /** DELETE /creatives/{id}/atoms/{atomId} — remove atom */
    @DeleteMapping("/{id}/atoms/{atomId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAtom(@PathVariable UUID id, @PathVariable UUID atomId) {
        creativeService.deleteAtom(id, atomId);
    }
}
