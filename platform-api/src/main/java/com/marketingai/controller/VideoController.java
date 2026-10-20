package com.marketingai.controller;

import com.marketingai.service.RlhaService;
import com.marketingai.config.AppProperties;
import com.marketingai.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

/**
 * Video Template Engine endpoints (vision §5.2, Phase 4-1 + 4-2).
 *
 * Footage upload + lifecycle stored in Spring Boot / PostgreSQL.
 * Heavy processing (Whisper, scene tagging, FFmpeg) delegated to FastAPI /video/*.
 * Marketer approval for renders before any creative is launched (vision §5.2).
 */
@Slf4j
@RestController
@RequestMapping("/video")
@RequiredArgsConstructor
public class VideoController {

    private final RestTemplate    restTemplate;

    private final RlhaService rlhaService;   // roadmap 9-20
    private final AppProperties   appProperties;
    private final StorageService  storageService;
    private final JdbcTemplate    jdbc;

    // ───────────────────────────────────────────────────────────────────────
    // 4-3: Footage upload
    // ───────────────────────────────────────────────────────────────────────

    @PostMapping(value = "/footage", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadFootage(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID creativeId) throws IOException {

        UUID footageId = UUID.randomUUID();
        String url = storageService.store(tenantId, footageId,
                file.getOriginalFilename(), file.getBytes());

        jdbc.update("""
                INSERT INTO video_footage
                    (id, tenant_id, creative_id, filename, storage_url, file_size_bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                footageId, tenantId, creativeId,
                file.getOriginalFilename(), url, file.getSize());

        log.info("Footage uploaded: tenantId={} footageId={} size={}",
                tenantId, footageId, file.getSize());

        return ResponseEntity.ok(Map.of(
                "footage_id",   footageId,
                "storage_url",  url,
                "filename",     Objects.requireNonNullElse(file.getOriginalFilename(), "upload")
        ));
    }

    @GetMapping("/footage")
    public List<Map<String, Object>> listFootage(@RequestAttribute("tenantId") UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, filename, storage_url, duration_sec, asset_tier,
                       jsonb_array_length(scenes) AS scene_count,
                       uploaded_at
                FROM video_footage
                ORDER BY uploaded_at DESC
                LIMIT 50
                """);
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4-1: Video pipeline proxies → FastAPI
    // ───────────────────────────────────────────────────────────────────────

    @PostMapping("/footage/{footageId}/transcribe")
    public Map<?, ?> transcribe(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID footageId,
            @RequestBody Map<String, Object> body) {

        body = new HashMap<>(body);
        body.put("footage_id", footageId.toString());
        body.put("tenant_id",  tenantId.toString());
        Map<?, ?> result = mlPost("/video/transcribe", body);

        // Persist transcript in footage record
        if (result != null && result.get("transcript") != null) {
            jdbc.update("UPDATE video_footage SET transcript = ? WHERE id = ?",
                    result.get("transcript"), footageId);
        }
        return result != null ? result : Map.of();
    }

    @PostMapping("/footage/{footageId}/tag-scenes")
    public Map<?, ?> tagScenes(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID footageId,
            @RequestBody Map<String, Object> body) {

        body = new HashMap<>(body);
        body.put("footage_id", footageId.toString());
        body.put("tenant_id",  tenantId.toString());
        Map<?, ?> result = mlPost("/video/tag-scenes", body);

        // Persist scenes JSON
        if (result != null && result.get("scenes") != null) {
            jdbc.update("UPDATE video_footage SET scenes = ?::jsonb WHERE id = ?",
                    scenesToJson(result.get("scenes")), footageId);
        }
        return result != null ? result : Map.of();
    }

    /** Roadmap 9-20: a rejected render is a signal — reason + note go to the montage-plan avoid list. */
    @PostMapping("/renders/{renderId}/reject")
    public ResponseEntity<Map<String, Object>> rejectRender(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestAttribute(value = "userId", required = false) UUID userId,
            @PathVariable UUID renderId,
            @RequestBody Map<String, String> body) {
        int n = jdbc.update("UPDATE video_renders SET killed_at = now() WHERE id = ? AND tenant_id = ?", renderId, tenantId);
        if (n == 0) return ResponseEntity.notFound().build();
        rlhaService.renderFeedback(tenantId, userId, renderId, "REJECTED", body.get("reason"), body.get("note"));
        return ResponseEntity.ok(Map.of("id", renderId, "status", "REJECTED", "reason", String.valueOf(body.get("reason"))));
    }

    @PostMapping("/footage/{footageId}/montage-plan")
    public Map<?, ?> montagePlan(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID footageId,
            @RequestBody Map<String, Object> body) {

        body = new HashMap<>(body);
        body.put("footage_id", footageId.toString());
        body.put("tenant_id",  tenantId.toString());
        body = new HashMap<>(body);
        body.putIfAbsent("avoid_notes", rlhaService.montageAvoidNotes(tenantId));   // roadmap 9-20
        return orEmpty(mlPost("/video/montage-plan", body));
    }

    @PostMapping("/footage/{footageId}/render")
    public Map<?, ?> startRender(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID footageId,
            @RequestBody Map<String, Object> body) {

        body = new HashMap<>(body);
        body.put("footage_id", footageId.toString());
        body.put("tenant_id",  tenantId.toString());
        Map<?, ?> result = orEmpty(mlPost("/video/render", body));

        // Create pending render record
        if (result.get("job_id") != null) {
            UUID renderId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO video_renders
                        (id, tenant_id, footage_id, render_spec, format, variation_type,
                         status, render_job_id)
                    VALUES (?, ?, ?, ?::jsonb, ?, 'CONCEPT', 'PENDING', ?)
                    """,
                    renderId, tenantId, footageId,
                    scenesToJson(body.get("plan")),
                    Objects.toString(body.getOrDefault("format", "9:16"), "9:16"),
                    result.get("job_id"));
        }
        return result;
    }

    /** Roadmap 9-13: SRT/ASS from Whisper segments (or a transcript with naive timing). */
    @PostMapping("/subtitles")
    public Map<?, ?> subtitles(@RequestAttribute("tenantId") UUID tenantId,
                               @RequestBody Map<String, Object> body) {
        body = new HashMap<>(body);
        body.put("tenant_id", tenantId.toString());
        return orEmpty(mlPost("/video/subtitles", body));
    }

    /** Roadmap 9-14: text-to-speech voice-over; returns audio_url + VOICE_* atoms for the render step. */
    @PostMapping("/tts")
    public Map<?, ?> tts(@RequestAttribute("tenantId") UUID tenantId,
                         @RequestBody Map<String, Object> body) {
        body = new HashMap<>(body);
        body.put("tenant_id", tenantId.toString());
        return orEmpty(mlPost("/video/tts", body));
    }

    @GetMapping("/render/{jobId}/status")
    public Map<?, ?> getRenderStatus(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable String jobId) {
        Map<?, ?> result = orEmpty(mlGet("/video/render/" + jobId));

        // Sync completed/failed status back to video_renders so approveRender can work.
        // FastAPI holds job state in Redis; Spring Boot is the source of truth for DB.
        String status = (String) result.get("status");
        if ("completed".equals(status) || "failed".equals(status)) {
            String storageUrl = (String) result.get("storage_url");
            Number renderMs   = (Number) result.get("render_time_ms");
            Number fileSize   = (Number) result.get("file_size_bytes");
            jdbc.update("""
                    UPDATE video_renders
                       SET status           = ?,
                           storage_url      = COALESCE(?, storage_url),
                           render_time_ms   = COALESCE(?, render_time_ms),
                           file_size_bytes  = COALESCE(?, file_size_bytes)
                     WHERE render_job_id = ?
                       AND tenant_id    = ?
                       AND status NOT IN ('COMPLETED','FAILED')
                    """,
                    status.toUpperCase(),
                    storageUrl,
                    renderMs   != null ? renderMs.intValue()  : null,
                    fileSize   != null ? fileSize.longValue()  : null,
                    jobId, tenantId);
        }
        return result;
    }

    // ───────────────────────────────────────────────────────────────────────
    // 4-2: Variation generation
    // ───────────────────────────────────────────────────────────────────────

    @PostMapping("/footage/{footageId}/generate-variations")
    public Map<?, ?> generateVariations(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID footageId,
            @RequestBody Map<String, Object> body) {

        body = new HashMap<>(body);
        body.put("footage_id", footageId.toString());
        body.put("tenant_id",  tenantId.toString());
        return orEmpty(mlPost("/video/generate-variations", body));
    }

    // ───────────────────────────────────────────────────────────────────────
    // Render management + marketer approval
    // ───────────────────────────────────────────────────────────────────────

    @GetMapping("/renders")
    public List<Map<String, Object>> listRenders(@RequestAttribute("tenantId") UUID tenantId) {
        return jdbc.queryForList("""
                SELECT id, footage_id, creative_id, format, variation_type,
                       status, storage_url, approved_at, killed_at, created_at
                FROM video_renders
                ORDER BY created_at DESC
                LIMIT 100
                """);
    }

    /**
     * Marketer approves a completed render (vision §5.2: approval required before launch).
     * Roadmap 4-5: latest FACE/MUSIC/AI_LABEL checks must be PASS or overridden before approve.
     */
    @PostMapping("/renders/{renderId}/approve")
    public ResponseEntity<Map<String, Object>> approveRender(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestAttribute("userId")   UUID userId,
            @PathVariable UUID renderId) {

        // 4-5 compliance gate: all media check types must pass or be overridden
        List<String> mediaCheckTypes = List.of("FACE", "MUSIC", "AI_LABEL");
        for (String checkType : mediaCheckTypes) {
            List<Map<String, Object>> rows2 = jdbc.queryForList("""
                    SELECT result, overridden_by
                    FROM compliance_checks
                    WHERE entity_id = ?
                      AND check_type = ?
                    ORDER BY created_at DESC
                    LIMIT 1
                    """, renderId, checkType);
            if (!rows2.isEmpty()) {
                Map<String, Object> row = rows2.get(0);
                String result = (String) row.get("result");
                Object overriddenBy = row.get("overridden_by");
                if ("FAIL".equals(result) && overriddenBy == null) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Media compliance check " + checkType + " is " + result
                                    + ". Run /compliance/media/check or override before approving.");
                }
            }
        }

        int rows = jdbc.update("""
                UPDATE video_renders
                   SET approved_by = ?,
                       approved_at = now(),
                       status      = 'COMPLETED'
                 WHERE id        = ?
                   AND status    = 'COMPLETED'
                   AND approved_at IS NULL
                """, userId, renderId);
        rlhaService.renderFeedback(tenantId, userId, renderId, "APPROVED", null, null);   // roadmap 9-20

        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Render not found or already approved / not yet completed");
        }

        log.info("Render {} approved by user {}", renderId, userId);
        return ResponseEntity.ok(Map.of(
                "render_id",   renderId,
                "approved",    true,
                "approved_by", userId
        ));
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    private Map<?, ?> mlPost(String path, Object body) {
        String url = appProperties.getMlServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        try {
            @SuppressWarnings("unchecked")
            Map<?, ?> result = restTemplate.postForObject(
                    url, new HttpEntity<>(body, headers), Map.class);
            return result;
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(
                    HttpStatus.valueOf(e.getStatusCode().value()), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("ML call {} failed: {}", path, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service unavailable");
        }
    }

    private Map<?, ?> mlGet(String path) {
        String url = appProperties.getMlServiceUrl() + path;
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-API-Key", appProperties.getInternalApiKey());
        try {
            @SuppressWarnings("unchecked")
            Map<?, ?> result = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            return result;
        } catch (HttpClientErrorException e) {
            throw new ResponseStatusException(
                    HttpStatus.valueOf(e.getStatusCode().value()), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("ML GET {} failed: {}", path, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ML service unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> orEmpty(Map<?, ?> m) {
        return m != null ? m : Map.of();
    }

    private static String scenesToJson(Object obj) {
        if (obj == null) return "[]";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return "[]";
        }
    }
}
