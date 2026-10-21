package com.marketingai.controller;

import com.marketingai.config.AppProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Serves locally stored creative files.
 * Only active when app.storage.type=local (default for dev).
 * In production (R2), file URLs are direct R2 links — this controller is unused.
 */
@RestController
@RequestMapping("/files")
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
@RequiredArgsConstructor
public class FileController {

    private final AppProperties props;
    private final JdbcTemplate jdbc;

    @GetMapping("/{tenantId}/{creativeId}/{filename}")
    public ResponseEntity<Resource> serve(
            @PathVariable UUID tenantId,
            @PathVariable UUID creativeId,
            @PathVariable String filename) {

        Path file = Path.of(props.getStorage().getLocalPath(),
                tenantId.toString(), creativeId.toString(), sanitize(filename));

        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        // Roadmap Phase 11 (rule 2): with creative_download_mode = PREVIEW_ONLY the final render is not downloadable —
        // it reaches Meta only through the cabinet's publish flow. Thumbnails stay available.
        if (previewOnly(tenantId) && isFinalRender(tenantId, creativeId, filename)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header("X-Error", "final renders are published from the cabinet; download is preview-only for this tenant")
                    .build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = detectContentType(filename);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private boolean previewOnly(UUID tenantId) {
        try {
            java.util.List<String> rows = jdbc.queryForList(
                    "SELECT settings->>'creative_download_mode' FROM tenants WHERE id = ?", String.class, tenantId);
            return !rows.isEmpty() && "PREVIEW_ONLY".equalsIgnoreCase(rows.get(0));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFinalRender(UUID tenantId, UUID creativeId, String filename) {
        try {
            java.util.List<String> rows = jdbc.queryForList(
                    "SELECT file_url FROM creatives WHERE id = ? AND tenant_id = ?", String.class, creativeId, tenantId);
            if (rows.isEmpty() || rows.get(0) == null) return false;
            String url = rows.get(0);
            return url.endsWith("/" + sanitize(filename));
        } catch (Exception e) {
            return false;
        }
    }

    private String sanitize(String filename) {
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String detectContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
