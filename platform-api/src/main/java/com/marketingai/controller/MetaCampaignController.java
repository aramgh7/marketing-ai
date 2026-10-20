package com.marketingai.controller;

import com.marketingai.dto.meta.AdSetRequest;
import com.marketingai.dto.meta.AdSetResponse;
import com.marketingai.dto.meta.CampaignRequest;
import com.marketingai.dto.meta.CampaignResponse;
import com.marketingai.dto.meta.RecommendedBatchResponse;
import com.marketingai.dto.meta.TestBatchRequest;
import com.marketingai.dto.meta.WinnerResponse;
import com.marketingai.security.TenantContext;
import com.marketingai.service.ExplorationBudgetService;
import com.marketingai.service.MetaCampaignService;
import com.marketingai.service.TwoContourService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for Meta Ads campaigns and ad sets.
 * Base paths follow architecture.md §5.1: /campaigns and /ad-sets
 */
@RestController
@RequiredArgsConstructor
public class MetaCampaignController {

    private final MetaCampaignService campaignService;
    private final TwoContourService twoContourService;
    private final ExplorationBudgetService explorationService;
    private final JdbcTemplate jdbc;

    // ─── Campaigns ──────────────────────────────────────────────────────────

    @PostMapping("/campaigns")
    public ResponseEntity<CampaignResponse> createCampaign(
            @Valid @RequestBody CampaignRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaignService.createCampaign(currentTenant(), req));
    }

    @GetMapping("/campaigns")
    public List<CampaignResponse> listCampaigns() {
        return campaignService.listCampaigns(currentTenant());
    }

    @PostMapping("/campaigns/{id}/pause")
    public CampaignResponse pauseCampaign(@PathVariable UUID id) {
        return campaignService.pauseCampaign(currentTenant(), id);
    }

    @PostMapping("/campaigns/{id}/activate")
    public CampaignResponse activateCampaign(@PathVariable UUID id) {
        return campaignService.activateCampaign(currentTenant(), id);
    }

    /**
     * Transfer winning ad set from TEST contour to SCALE campaign.
     * Implements two-contour structure per vision §6.5.
     */
    @PostMapping("/campaigns/transfer-to-scale")
    public CampaignResponse transferToScale(@RequestParam UUID winnerAdSetId) {
        return campaignService.transferToScale(currentTenant(), winnerAdSetId);
    }

    // ─── Two-Contour Workflow (§6.5) ────────────────────────────────────────

    /**
     * Recommend next test batch using Exploration Budget (§6.4).
     * Returns exploitation (EVI-ranked) + exploration (new/untested) candidate slots.
     * The marketer reviews and submits the chosen creatives via /launch-batch.
     */
    @GetMapping("/campaigns/{id}/recommend-batch")
    public RecommendedBatchResponse recommendBatch(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "9") int batchSize) {
        return explorationService.recommendBatch(currentTenant(), id, batchSize);
    }

    /**
     * Launch a batch of ad sets in a TEST campaign with equal budgets.
     * All ad sets in the batch get the same perAdSetDailyBudget.
     */
    @PostMapping("/campaigns/{id}/launch-batch")
    public ResponseEntity<List<AdSetResponse>> launchTestBatch(
            @PathVariable UUID id,
            @Valid @RequestBody TestBatchRequest req) {
        req.setCampaignId(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(twoContourService.launchTestBatch(currentTenant(), req));
    }

    /**
     * Select the winner from the latest test batch using shrinkage-adjusted estimates.
     * Returns a recommendation for the marketer before starting confirmation run.
     */
    @GetMapping("/campaigns/{id}/winner")
    public WinnerResponse selectWinner(@PathVariable UUID id) {
        return twoContourService.selectWinner(currentTenant(), id);
    }

    /**
     * Start confirmation run for the winner ad set.
     * Creates a new ad set with 1.5× budget to verify the result reproduces.
     */
    @PostMapping("/campaigns/{id}/confirmation-run")
    public CampaignResponse startConfirmationRun(
            @PathVariable UUID id,
            @RequestParam UUID winnerAdSetId) {
        return twoContourService.startConfirmationRun(currentTenant(), id, winnerAdSetId);
    }

    /**
     * Confirm the winner passed the confirmation run and transfer to SCALE.
     * Should be called after confirmation run has been live ≥ 3 days.
     */
    @PostMapping("/campaigns/{id}/confirm-and-transfer")
    public CampaignResponse confirmAndTransfer(
            @PathVariable UUID id,
            @RequestParam UUID winnerAdSetId) {
        return twoContourService.confirmAndTransfer(currentTenant(), id, winnerAdSetId);
    }

    /**
     * Mark confirmation run as FAILED (marketer decision — result did not reproduce).
     * Resets confirmationStatus to FAILED so a new winner can be selected.
     */
    @PostMapping("/campaigns/{id}/fail-confirmation")
    public CampaignResponse failConfirmation(@PathVariable UUID id) {
        return twoContourService.failConfirmation(currentTenant(), id);
    }

    // ─── Ad Sets ────────────────────────────────────────────────────────────

    @PostMapping("/ad-sets")
    public ResponseEntity<AdSetResponse> createAdSet(
            @Valid @RequestBody AdSetRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(campaignService.createAdSet(currentTenant(), req));
    }

    @GetMapping("/ad-sets")
    public List<AdSetResponse> listAdSets(@RequestParam UUID campaignId) {
        return campaignService.listAdSets(currentTenant(), campaignId);
    }

    /**
     * Campaign-setup guard: checks conversion event volume for meaningful optimization (vision §16.1).
     * Meta requires ≥50 conversions/week before optimizing for conversion events.
     */
    @GetMapping("/campaigns/{id}/setup-guard")
    public ResponseEntity<Map<String, Object>> setupGuard(@PathVariable UUID id) {
        UUID tenantId = currentTenant();

        Integer convCount = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT e.id)
            FROM events e
            JOIN sessions s ON s.id = e.session_id
            WHERE s.tenant_id = ?
              AND e.event_type = 'CONVERSION'
              AND e.event_time > NOW() - INTERVAL '7 days'
            """, Integer.class, tenantId);

        int n = convCount != null ? convCount : 0;
        boolean sufficient = n >= 50;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("campaign_id",         id);
        resp.put("conversion_count_7d", n);
        resp.put("min_required",        50);
        resp.put("has_enough_events",   sufficient);
        resp.put("can_launch",          sufficient);
        resp.put("recommendation", sufficient
            ? "Sufficient conversion volume for Meta optimization."
            : String.format("Only %d conversion events in last 7 days. Meta recommends ≥50/week. Consider broader audience or longer attribution window.", n));
        return ResponseEntity.ok(resp);
    }

    // ─── Internal ───────────────────────────────────────────────────────────

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }
}
