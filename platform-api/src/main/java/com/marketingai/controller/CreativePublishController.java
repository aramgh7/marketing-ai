package com.marketingai.controller;

import com.marketingai.service.CreativePublishService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Roadmap Phase 11-3: publish a creative to Meta from the cabinet (PAUSED ad with the platform's destination
 * URL and url_tags), activate after the moderation verdict, pause. See .ai/creative-publishing.md.
 */
@RestController
@RequestMapping("/creatives")
@RequiredArgsConstructor
public class CreativePublishController {

    private final CreativePublishService publish;

    @GetMapping("/{id}/publish-preview")
    public Map<String, Object> preview(@PathVariable UUID id,
                                       @RequestParam(value = "entry_slug", required = false) String entrySlug,
                                       @RequestParam(value = "host", required = false) String host) {
        return publish.preview(id, entrySlug, host);
    }

    @PostMapping("/{id}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> publish(@PathVariable UUID id,
                                       @RequestAttribute(value = "userId", required = false) UUID userId,
                                       @RequestBody Map<String, Object> body) {
        return publish.publish(id, body, userId);
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@PathVariable UUID id,
                                        @RequestAttribute(value = "userId", required = false) UUID userId) {
        return publish.activate(id, userId);
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable UUID id,
                                     @RequestAttribute(value = "userId", required = false) UUID userId) {
        return publish.pause(id, userId);
    }
}
