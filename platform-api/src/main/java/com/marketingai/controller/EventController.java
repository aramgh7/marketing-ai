package com.marketingai.controller;

import com.marketingai.dto.event.EventBatchRequest;
import com.marketingai.dto.event.EventBatchResponse;
import com.marketingai.service.EventIngestionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventIngestionService ingestionService;

    /**
     * Public endpoint — called by tracker.ts from landing pages.
     * No JWT required; tenant_id is embedded in each event by the tracker.
     *
     * Headers used for fraud scoring (Phase 0-6):
     *  CF-Connecting-IP / X-Forwarded-For → real visitor IP
     *  User-Agent                         → bot detection
     *  CF-Bot-Score                        → Cloudflare bot confidence (0-99, lower = more bot)
     */
    @PostMapping
    public ResponseEntity<EventBatchResponse> ingest(
            @Valid @RequestBody EventBatchRequest req,
            HttpServletRequest httpRequest) {

        String ip         = resolveIp(httpRequest);
        String userAgent  = httpRequest.getHeader("User-Agent");
        Integer cfBotScore = parseCfBotScore(httpRequest.getHeader("CF-Bot-Score"));

        EventBatchResponse response = ingestionService.ingest(
                req.getEvents(), ip, userAgent, cfBotScore);
        return ResponseEntity.ok(response);
    }

    /** Cloudflare sets CF-Connecting-IP; behind nginx use X-Forwarded-For. */
    private String resolveIp(HttpServletRequest req) {
        String cf = req.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();

        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // XFF can be a comma-separated list; first entry = original client
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private Integer parseCfBotScore(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Integer.parseInt(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
