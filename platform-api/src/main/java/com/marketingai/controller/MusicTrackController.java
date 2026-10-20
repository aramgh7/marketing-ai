package com.marketingai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant music library (roadmap 9-15; vision §5.2). Metadata only — the audio file itself is
 * uploaded to R2 through the existing file upload path and referenced by URL. The ML render mixes
 * a chosen track under the speech; no generative music provider is used.
 *
 *   GET    /music/tracks                 active tracks
 *   POST   /music/tracks                 {title, url, licence, licence_ref?, bpm?, mood?, duration_sec?}
 *   DELETE /music/tracks/{id}            deactivate
 */
@RestController
@RequestMapping("/music")
@RequiredArgsConstructor
public class MusicTrackController {

    private static final Set<String> LICENCES = Set.of("royalty_free", "licensed", "owned");

    private final JdbcTemplate jdbc;

    @GetMapping("/tracks")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestAttribute("tenantId") UUID tenantId) {
        return ResponseEntity.ok(jdbc.queryForList("""
                SELECT id, title, url, licence, licence_ref, bpm, mood, duration_sec, created_at
                FROM music_tracks WHERE tenant_id = ? AND is_active = TRUE
                ORDER BY mood NULLS LAST, title
                """, tenantId));
    }

    @PostMapping("/tracks")
    public ResponseEntity<Map<String, Object>> create(@RequestAttribute("tenantId") UUID tenantId,
                                                      @RequestBody Map<String, Object> body) {
        String title = str(body.get("title"));
        String url = str(body.get("url"));
        String licence = str(body.get("licence"));
        if (title == null || url == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "title and url required"));
        }
        if (licence == null || !LICENCES.contains(licence.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of("error", "licence must be one of " + LICENCES));
        }
        Double bpm = num(body.get("bpm"));
        Double duration = num(body.get("duration_sec"));
        if ((bpm != null && bpm <= 0) || (duration != null && duration <= 0)) {
            return ResponseEntity.badRequest().body(Map.of("error", "bpm and duration_sec must be positive"));
        }
        UUID id = UUID.randomUUID();
        String mood = str(body.get("mood"));
        jdbc.update("""
                INSERT INTO music_tracks (id, tenant_id, title, url, licence, licence_ref, bpm, mood, duration_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, title, url, licence.toLowerCase(), str(body.get("licence_ref")),
                bpm, mood == null ? null : mood.toLowerCase(), duration);
        return ResponseEntity.ok(Map.of("id", id, "status", "saved"));
    }

    @DeleteMapping("/tracks/{id}")
    public ResponseEntity<Map<String, Object>> deactivate(@RequestAttribute("tenantId") UUID tenantId,
                                                          @PathVariable UUID id) {
        int n = jdbc.update("UPDATE music_tracks SET is_active = FALSE WHERE id = ? AND tenant_id = ?", id, tenantId);
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.ok(Map.of("status", "deactivated"));
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double num(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}
