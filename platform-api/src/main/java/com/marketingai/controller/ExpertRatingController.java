package com.marketingai.controller;

import com.marketingai.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expert ratings for creatives (3-17 autonomy levels).
 * POST /api/v1/creatives/{id}/expert-rating — submit a rating
 * GET  /api/v1/creatives/{id}/expert-rating — list ratings
 */
@RestController
@RequestMapping("/api/v1/creatives")
@RequiredArgsConstructor
public class ExpertRatingController {

    private final JdbcTemplate jdbc;

    record RatingRequest(int rating, boolean veto, String reason) {}

    @PostMapping("/{id}/expert-rating")
    public Map<String, Object> submitRating(
            @PathVariable UUID id,
            @RequestBody RatingRequest body) {
        UUID tenantId = currentTenant();

        if (body.rating() < 1 || body.rating() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rating must be 1-5");
        }

        UUID ratingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO expert_ratings (id, tenant_id, entity_type, entity_id, rating, veto, reason, created_at)
                VALUES (?, ?, 'CREATIVE', ?, ?, ?, ?, now())
                """, ratingId, tenantId, id, body.rating(), body.veto(), body.reason());

        return Map.of(
                "id", ratingId,
                "creative_id", id,
                "rating", body.rating(),
                "veto", body.veto(),
                "reason", body.reason() != null ? body.reason() : ""
        );
    }

    @GetMapping("/{id}/expert-rating")
    public List<Map<String, Object>> listRatings(@PathVariable UUID id) {
        UUID tenantId = currentTenant();
        return jdbc.queryForList("""
                SELECT id, entity_id AS creative_id, rating, veto, reason, rated_by, created_at
                FROM expert_ratings
                WHERE tenant_id = ? AND entity_type = 'CREATIVE' AND entity_id = ?
                ORDER BY created_at DESC
                """, tenantId, id);
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
