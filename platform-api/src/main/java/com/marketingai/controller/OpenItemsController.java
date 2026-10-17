package com.marketingai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 8-13: Open Items to Resolve with Client #1 (vision §19.16).
 * Returns a structured registry of 7 open questions for the onboarding call.
 */
@RestController
@RequestMapping("/api/v1/open-items")
public class OpenItemsController {

    private static final List<Map<String, Object>> OPEN_ITEMS = List.of(
        Map.of(
            "id",          "OI-1",
            "category",    "deal_cycle",
            "title",       "Deal cycle: first touch → funds",
            "description", "Establish the P50 and P90 days from first page view to funds received. " +
                           "Sets the attribution window (6-7) and holdout horizon.",
            "impact",      "HIGH",
            "owner",       "client",
            "status",      "OPEN"
        ),
        Map.of(
            "id",          "OI-2",
            "category",    "deal_cycle",
            "title",       "Fall-through rate after INVEST_COMMITTED",
            "description", "What fraction of verbally committed investors do not fund? And on what timeline? " +
                           "This is the churn-penalty input for the reward ladder.",
            "impact",      "HIGH",
            "owner",       "client",
            "status",      "OPEN"
        ),
        Map.of(
            "id",          "OI-3",
            "category",    "legal",
            "title",       "Reg CF waitlist: marketing to non-accredited leads",
            "description", "Counsel's answer on whether non-accredited leads (self_accredited=false) " +
                           "can receive a follow-up sequence before a Reg CF round is publicly announced. " +
                           "REGCF_WAITLIST audience is paused until cleared.",
            "impact",      "HIGH",
            "owner",       "counsel",
            "status",      "OPEN",
            "note",        "REGCF_WAITLIST audience MUST NOT be activated without legal clearance."
        ),
        Map.of(
            "id",          "OI-4",
            "category",    "funnel_priority",
            "title",       "Which funnel launches first: investor or grower-facing?",
            "description", "Determines the default weather_role (NARRATIVE vs DEMAND), " +
                           "offer schema, and atom set for Phase 8 go-live.",
            "impact",      "MEDIUM",
            "owner",       "client",
            "status",      "OPEN"
        ),
        Map.of(
            "id",          "OI-5",
            "category",    "ghl_data",
            "title",       "GHL data availability: booking distance, show by tz/slot",
            "description", "Confirm availability of: booking_distance_h (slot booked vs call time), " +
                           "show flag by timezone and country, show by time-of-day slot. " +
                           "Required for no-show propensity model (8-15) and slot overbooking.",
            "impact",      "MEDIUM",
            "owner",       "client",
            "status",      "OPEN"
        ),
        Map.of(
            "id",          "OI-6",
            "category",    "meta_pixel",
            "title",       "Meta: value update by event_id + event naming",
            "description", "Confirm Meta account supports value updates via event_id deduplication. " +
                           "Decide: CompleteRegistration vs custom event for CALL_HELD. " +
                           "Clarify financial-services ad rules per delivery country.",
            "impact",      "HIGH",
            "owner",       "client+meta",
            "status",      "OPEN"
        ),
        Map.of(
            "id",          "OI-7",
            "category",    "vertical",
            "title",       "Universal Model vertical for client #1",
            "description", "Client #1 niche is 'investor lead-gen / high-ticket financial offers', " +
                           "not 'B2B manufacturing'. Decide the first Universal Model flywheel vertical " +
                           "(0-5a) to set niche_key for benchmark comparisons.",
            "impact",      "LOW",
            "owner",       "platform+client",
            "status",      "OPEN"
        )
    );

    @GetMapping
    public ResponseEntity<Map<String, Object>> getOpenItems(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category) {

        List<Map<String, Object>> filtered = OPEN_ITEMS.stream()
            .filter(item -> status == null   || status.equalsIgnoreCase((String) item.get("status")))
            .filter(item -> category == null || category.equalsIgnoreCase((String) item.get("category")))
            .toList();

        long highCount   = filtered.stream().filter(i -> "HIGH".equals(i.get("impact"))).count();
        long openCount   = filtered.stream().filter(i -> "OPEN".equals(i.get("status"))).count();
        long blockedCount = filtered.stream()
            .filter(i -> "OPEN".equals(i.get("status")) && i.containsKey("note")).count();

        return ResponseEntity.ok(Map.of(
            "total",   filtered.size(),
            "open",    openCount,
            "high_impact_open", highCount,
            "legal_blocked",    blockedCount,
            "items",   filtered
        ));
    }
}
