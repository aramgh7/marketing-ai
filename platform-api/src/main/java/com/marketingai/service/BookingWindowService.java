package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic booking window based on no-show risk tier (roadmap 8-15; vision §19.18.2).
 * HIGH risk → nearer slots (1-3 days). LOW → standard 2-7 day window.
 */
@Service
@RequiredArgsConstructor
public class BookingWindowService {

    private final JdbcTemplate jdbc;

    public Map<String, Object> getBookingWindow(UUID tenantId, UUID sessionId) {
        List<Map<String, Object>> scores = jdbc.queryForList("""
            SELECT risk_tier, noshow_prob FROM noshow_scores
            WHERE session_id = ? AND tenant_id = ?
            ORDER BY scored_at DESC LIMIT 1
            """, sessionId, tenantId);

        String riskTier = scores.isEmpty() ? "UNKNOWN"
                : (String) scores.get(0).get("risk_tier");
        Double noshowProb = scores.isEmpty() ? null
                : ((Number) scores.get(0).get("noshow_prob")).doubleValue();

        Map<String, Object> window = new HashMap<>(buildWindow(riskTier));
        window.put("session_id",   sessionId);
        window.put("risk_tier",    riskTier);
        window.put("noshow_prob",  noshowProb);
        window.put("scored",       !scores.isEmpty());
        return window;
    }

    private Map<String, Object> buildWindow(String riskTier) {
        return switch (riskTier) {
            case "HIGH" -> Map.of(
                "min_days_out",    1,
                "max_days_out",    3,
                "slot_window_h",   24,
                "preferred_times", List.of("09:00", "11:00", "14:00"),
                "note",            "Nearer slots offered — high no-show risk"
            );
            case "MED" -> Map.of(
                "min_days_out",    1,
                "max_days_out",    5,
                "slot_window_h",   72,
                "preferred_times", List.of("10:00", "14:00", "16:00"),
                "note",            "Earlier slots preferred — medium risk"
            );
            default -> Map.of(
                "min_days_out",    2,
                "max_days_out",    7,
                "slot_window_h",   96,
                "preferred_times", List.of("10:00", "14:00"),
                "note",            "Standard 4-day booking window"
            );
        };
    }
}
