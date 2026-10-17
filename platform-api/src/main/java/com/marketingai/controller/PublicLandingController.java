package com.marketingai.controller;

import com.marketingai.dto.variant.VariantResponse;
import com.marketingai.service.VariantService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Public (unauthenticated) endpoints used by the Next.js edge middleware and
 * SSR landing page renderer.  No JWT required.
 */
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicLandingController {

    private final VariantService variantService;

    /**
     * Returns the ACTIVE variant for the given slug.
     * Called by Next.js SSR page to render landing blocks.
     */
    @GetMapping("/l/{slug}")
    public VariantResponse getActiveVariant(@PathVariable String slug) {
        return variantService.getActiveBySlug(slug);
    }
}
