package com.marketingai.service;

import com.marketingai.dto.meta.AdSetResponse;
import com.marketingai.dto.meta.CampaignResponse;
import com.marketingai.dto.meta.TestBatchRequest;
import com.marketingai.dto.meta.WinnerResponse;
import com.marketingai.entity.MetaAdSet;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.entity.Tenant;
import com.marketingai.entity.ThompsonArm;
import com.marketingai.repository.MetaAdSetRepository;
import com.marketingai.repository.MetaCampaignRepository;
import com.marketingai.repository.TenantRepository;
import com.marketingai.repository.ThompsonArmRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the two-contour exploration/exploitation lifecycle:
 *
 *   TEST contour  → batch launch (equal budgets) → winner selection (shrinkage)
 *                 → confirmation run → transfer to SCALE
 *
 * SCALE contour is "mostly hands-off" — Meta CBO/Advantage+ manages delivery.
 * We only rotate burned-out creatives there.
 *
 * Rules enforced per vision §6.5:
 *   - Equal budgets in test batch for clean comparison
 *   - Budget changes ≤ 20% per step (see BudgetChangeGuard)
 *   - Winner's curse mitigation via James-Stein shrinkage
 *   - Confirmation run before transfer to SCALE
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwoContourService {

    private final MetaCampaignRepository campaignRepo;
    private final MetaAdSetRepository adSetRepo;
    private final ThompsonArmRepository armRepo;
    private final MetaApiService metaApi;
    private final MetaCampaignService campaignService;
    private final TenantRepository tenantRepo;
    private final ExplorationBudgetService explorationService;
    private final JdbcTemplate jdbc;
    private final LearningPhaseService learningPhaseService;

    // ─── 1. Test Batch Launch ─────────────────────────────────────────────

    /**
     * Launch a batch of ad sets in a TEST campaign with equal budgets.
     * All ad sets in this batch share the same batch_id for later winner selection.
     *
     * Vision §6.5: "new creatives launched IN BATCHES (not one by one), equal budgets"
     */
    @Transactional
    public List<AdSetResponse> launchTestBatch(UUID tenantId, TestBatchRequest req) {
        MetaCampaign campaign = requireCampaign(tenantId, req.getCampaignId());
        if (!"TEST".equals(campaign.getContour())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Batch launch is only for TEST contour campaigns");
        }

        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();
        if (adAccountId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant has no Meta ad account ID configured");
        }

        // Anchor placement rule (vision §11.2): batch must contain ≥2 anchor ad sets
        long anchorCount = req.getAdSets().stream()
                .filter(a -> a.isAnchor()).count();
        if (anchorCount < 2) {
            String overrideReason = req.getAnchorOverrideReason();
            if (overrideReason == null || overrideReason.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Batch requires at least 2 anchor ad sets (vision §11.2). " +
                        "Provide anchorOverrideReason to bypass.");
            }
            // Log override
            jdbc.update("""
                    INSERT INTO anchor_override_log (tenant_id, anchor_count, reason)
                    VALUES (?, ?, ?)
                    """, tenantId, (int) anchorCount, overrideReason);
            log.warn("Anchor rule bypassed by tenant {} — anchorCount={} reason={}",
                    tenantId, anchorCount, overrideReason);
        }

        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now();
        List<AdSetResponse> created = new ArrayList<>();

        for (var adSetReq : req.getAdSets()) {
            // Override budget — all ad sets get equal budgets per §6.5
            adSetReq.setDailyBudget(req.getPerAdSetDailyBudget());

            String name = adSetReq.getName() != null ? adSetReq.getName()
                    : "batch-" + batchId.toString().substring(0, 8) + "-" + (created.size() + 1);

            String metaAdSetId = metaApi.createAdSet(
                    token, adAccountId, campaign.getMetaCampaignId(),
                    name, adSetReq.getAudienceSpec(), req.getPerAdSetDailyBudget());

            boolean isExploration = req.getExplorationCreativeIds() != null
                    && adSetReq.getCreativeId() != null
                    && req.getExplorationCreativeIds().contains(adSetReq.getCreativeId());

            MetaAdSet adSet = new MetaAdSet();
            adSet.setTenantId(tenantId);
            adSet.setCampaignId(campaign.getId());
            adSet.setMetaAdSetId(metaAdSetId);
            adSet.setCreativeId(adSetReq.getCreativeId());
            adSet.setAudienceSpec(adSetReq.getAudienceSpec());
            adSet.setDailyBudget(req.getPerAdSetDailyBudget());
            adSet.setStatus("PAUSED");
            adSet.setBatchId(batchId);
            adSet.setBatchLaunchedAt(now);
            adSet.setExplorationSlot(isExploration);
            adSet.setAnchor(adSetReq.isAnchor());

            // Learning Phase Log (vision §6.5): record phase before LAUNCH
            learningPhaseService.logBeforeAction(tenantId, metaAdSetId, "LAUNCH",
                    adSet.getLearningPhase());

            created.add(campaignService.toAdSetResponse(adSetRepo.save(adSet)));
        }

        log.info("Launched TEST batch {} with {} ad sets for campaign {} (tenant {})",
                batchId, created.size(), campaign.getId(), tenantId);
        return created;
    }

    // ─── 2. Winner Selection with Shrinkage ───────────────────────────────

    /**
     * Select the best ad set from the latest batch in a TEST campaign.
     * Applies James-Stein shrinkage to mitigate winner's curse (§6.5).
     *
     * Shrinkage formula:
     *   θ_shrunk_i = group_mean + (1 - shrinkage_factor) × (θ_raw_i - group_mean)
     *   shrinkage_factor = c / (c + n_i)   where c = 10 (pseudo-count, ~prior strength)
     *
     * Result: ad sets with few impressions are pulled toward group mean;
     *         high-data ad sets retain most of their raw estimate.
     */
    public WinnerResponse selectWinner(UUID tenantId, UUID campaignId) {
        requireCampaign(tenantId, campaignId);

        List<MetaAdSet> allAdSets = adSetRepo.findAllByCampaignId(campaignId)
                .stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .filter(a -> a.getBatchId() != null)
                .toList();

        if (allAdSets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No batched ad sets found for this campaign");
        }

        // Use the most recent batch
        UUID latestBatch = allAdSets.stream()
                .max(Comparator.comparing(a -> a.getBatchLaunchedAt() != null
                        ? a.getBatchLaunchedAt() : Instant.EPOCH))
                .map(MetaAdSet::getBatchId)
                .orElseThrow();

        List<MetaAdSet> batch = allAdSets.stream()
                .filter(a -> latestBatch.equals(a.getBatchId()))
                .toList();

        // Compute raw estimates from Thompson arms (alpha/(alpha+beta))
        List<AdSetScore> scores = batch.stream()
                .map(adSet -> {
                    ThompsonArm arm = armRepo
                            .findByVariantIdAndContextKey(adSet.getId(), "meta_adset")
                            .orElse(null);
                    double alpha = arm != null ? arm.getAlpha() : 1.0;
                    double beta  = arm != null ? arm.getBeta()  : 1.0;
                    double raw = alpha / (alpha + beta);
                    long n = Math.round(alpha + beta - 2); // subtract the (1,1) prior
                    return new AdSetScore(adSet, raw, n);
                })
                .toList();

        // Shrinkage: James-Stein toward group mean
        double groupMean = scores.stream().mapToDouble(s -> s.raw).average().orElse(0.5);
        int c = 10; // prior pseudo-count

        AdSetScore winner = scores.stream()
                .map(s -> {
                    double sf = (double) c / (c + s.observations);
                    s.shrunk = groupMean + (1 - sf) * (s.raw - groupMean);
                    return s;
                })
                .max(Comparator.comparingDouble(s -> s.shrunk))
                .orElseThrow();

        String recommendation = buildRecommendation(winner, groupMean);

        log.info("Winner selected: adSet={}, raw={:.3f}, shrunk={:.3f} (campaign={}, tenant={})",
                winner.adSet.getId(), winner.raw, winner.shrunk, campaignId, tenantId);

        return WinnerResponse.builder()
                .adSetId(winner.adSet.getId())
                .metaAdSetId(winner.adSet.getMetaAdSetId())
                .creativeId(winner.adSet.getCreativeId())
                .rawEstimate(winner.raw)
                .shrunkEstimate(winner.shrunk)
                .impressions(winner.observations)
                .spendTotal(winner.adSet.getSpendTotal() != null ? winner.adSet.getSpendTotal() : 0f)
                .recommendation(recommendation)
                .build();
    }

    // ─── 3. Confirmation Run ─────────────────────────────────────────────

    /**
     * Start a confirmation run for the winner before transfer to SCALE.
     * Creates a new ad set (clone of winner) with 1.5× budget.
     *
     * Vision §6.5: "short run with increased budget to verify result reproduces"
     * Sets campaign.confirmationStatus = RUNNING.
     */
    @Transactional
    public CampaignResponse startConfirmationRun(UUID tenantId, UUID campaignId, UUID winnerAdSetId) {
        MetaCampaign campaign = requireCampaign(tenantId, campaignId);

        if (!"NONE".equals(campaign.getConfirmationStatus())
                && !"FAILED".equals(campaign.getConfirmationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Confirmation run already in progress or passed (status="
                            + campaign.getConfirmationStatus() + ")");
        }

        MetaAdSet winner = adSetRepo.findById(winnerAdSetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ad set not found"));
        if (!winner.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();

        // Confirmation budget = 1.5× winner budget (increased but not huge leap)
        float confirmBudget = winner.getDailyBudget() != null
                ? Math.round(winner.getDailyBudget() * 1.5f * 100) / 100f
                : campaign.getDailyBudget() != null ? campaign.getDailyBudget() : 50f;

        // Validate budget change ≤ 20% rule vs current campaign budget
        validateBudgetStep(campaign.getDailyBudget(), confirmBudget, campaign.getId().toString());

        String confirmMetaAdSetId = metaApi.createAdSet(
                token, adAccountId, campaign.getMetaCampaignId(),
                "confirmation-" + winner.getMetaAdSetId(),
                winner.getAudienceSpec(),
                confirmBudget);

        // Persist confirmation ad set
        MetaAdSet confirmAdSet = new MetaAdSet();
        confirmAdSet.setTenantId(tenantId);
        confirmAdSet.setCampaignId(campaign.getId());
        confirmAdSet.setMetaAdSetId(confirmMetaAdSetId);
        confirmAdSet.setCreativeId(winner.getCreativeId());
        confirmAdSet.setAudienceSpec(winner.getAudienceSpec());
        confirmAdSet.setDailyBudget(confirmBudget);
        confirmAdSet.setStatus("ACTIVE");
        adSetRepo.save(confirmAdSet);

        // Track on campaign
        campaign.setConfirmationAdSetId(confirmMetaAdSetId);
        campaign.setConfirmationStatus("RUNNING");
        campaignRepo.save(campaign);

        log.info("Confirmation run started: metaAdSetId={} budget={} (campaign={}, tenant={})",
                confirmMetaAdSetId, confirmBudget, campaignId, tenantId);

        return campaignService.toResponse(campaign);
    }

    /**
     * Evaluate the confirmation run. If it passes (spend > 0 and no manual override),
     * transfer winner to SCALE and mark confirmationStatus = PASSED.
     *
     * In practice: call after the confirmation run has been live ≥ 3 days.
     * The marketer confirms via UI that the result reproduces before this endpoint is called.
     */
    @Transactional
    public CampaignResponse confirmAndTransfer(UUID tenantId, UUID campaignId, UUID winnerAdSetId) {
        MetaCampaign campaign = requireCampaign(tenantId, campaignId);

        if (!"RUNNING".equals(campaign.getConfirmationStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No running confirmation (status=" + campaign.getConfirmationStatus() + ")");
        }

        // Validate confirmation ad set has accrued some spend
        adSetRepo.findByMetaAdSetId(campaign.getConfirmationAdSetId())
                .ifPresent(confirmAdSet -> {
                    float spend = confirmAdSet.getSpendTotal() != null ? confirmAdSet.getSpendTotal() : 0f;
                    if (spend < 1.0f) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "Confirmation run has not yet accrued spend. Wait and retry.");
                    }
                });

        // Transfer winner to SCALE
        campaignService.transferToScale(tenantId, winnerAdSetId);

        // Mark confirmation passed
        campaign.setConfirmationStatus("PASSED");
        campaignRepo.save(campaign);

        log.info("Confirmation PASSED, winner {} transferred to SCALE (campaign={}, tenant={})",
                winnerAdSetId, campaignId, tenantId);

        return campaignService.toResponse(campaign);
    }

    /**
     * Mark confirmation run as FAILED (marketer decision).
     * Campaign goes back to NONE so a new winner can be selected.
     */
    @Transactional
    public CampaignResponse failConfirmation(UUID tenantId, UUID campaignId) {
        MetaCampaign campaign = requireCampaign(tenantId, campaignId);

        Tenant tenant = requireTenant(tenantId);
        if (campaign.getConfirmationAdSetId() != null) {
            try {
                metaApi.setAdSetStatus(decodeToken(tenant),
                        campaign.getConfirmationAdSetId(), "PAUSED");
            } catch (Exception e) {
                log.warn("Could not pause confirmation ad set {}: {}", campaign.getConfirmationAdSetId(), e.getMessage());
            }
        }

        campaign.setConfirmationStatus("FAILED");
        campaignRepo.save(campaign);
        log.info("Confirmation FAILED for campaign {} (tenant {})", campaignId, tenantId);
        return campaignService.toResponse(campaign);
    }

    // ─── Block F §6: Bid Cap Recommendation ──────────────────────────────

    /**
     * Recommend a bid cap based on the P95 of historical CPA for this campaign.
     * Vision: bid cap prevents overpaying in Advantage+ while staying competitive.
     * Logged to bid_cap_log for audit trail.
     */
    @Transactional
    public java.util.Map<String, Object> recommendBidCap(UUID tenantId, UUID campaignId) {
        requireCampaign(tenantId, campaignId);

        // Pull recent CPA values from decision_log via spend & conversion events
        List<Double> cpaValues = jdbc.queryForList(
            """
            SELECT spend / NULLIF(conversions, 0) AS cpa
            FROM (
                SELECT
                    ms.id,
                    COALESCE(ms.spend_total, 0) AS spend,
                    COUNT(e.id) FILTER (WHERE e.event_type = 'CONVERSION') AS conversions
                FROM meta_ad_sets ms
                LEFT JOIN events e ON e.session_id IN (
                    SELECT id FROM sessions WHERE tenant_id = ?
                ) AND e.event_type = 'CONVERSION'
                AND e.created_at >= NOW() - INTERVAL '30 days'
                WHERE ms.campaign_id = ?
                  AND ms.tenant_id = ?
                GROUP BY ms.id, ms.spend_total
            ) sub
            WHERE spend > 0 AND conversions > 0
            """,
            Double.class, tenantId, campaignId, tenantId);

        if (cpaValues.isEmpty()) {
            return java.util.Map.of(
                "status", "INSUFFICIENT_DATA",
                "reason", "No CPA data in the last 30 days for this campaign"
            );
        }

        cpaValues.sort(Double::compareTo);
        int p95Index = (int) Math.ceil(0.95 * cpaValues.size()) - 1;
        double cpaP95 = cpaValues.get(Math.min(p95Index, cpaValues.size() - 1));
        // Round to 2 decimal places
        double recommendedCap = Math.round(cpaP95 * 100.0) / 100.0;

        // Log to audit table
        jdbc.update(
            """
            INSERT INTO bid_cap_log (tenant_id, campaign_id, recommended_cap, cpa_p95, basis)
            VALUES (?, ?, ?, ?, 'TWO_CONTOUR')
            """,
            tenantId, campaignId.toString(), recommendedCap, cpaP95);

        log.info("Bid cap recommendation: campaignId={} cap={} cpaP95={} n={}",
                campaignId, recommendedCap, cpaP95, cpaValues.size());

        return java.util.Map.of(
            "campaign_id",      campaignId.toString(),
            "recommended_cap",  recommendedCap,
            "cpa_p95",          cpaP95,
            "sample_size",      cpaValues.size(),
            "basis",            "TWO_CONTOUR"
        );
    }

    // ─── Block F §7: CREATIVE_TESTING_TOOL mode ───────────────────────────

    /**
     * Check whether the tenant is in CREATIVE_TESTING_TOOL launch_mode.
     * In this mode: all ad sets receive strictly equal budgets and equal impressions
     * target — no Thompson exploitation until the test window closes.
     */
    public boolean isCreativeTestingToolMode(UUID tenantId) {
        String mode = jdbc.queryForObject(
            "SELECT launch_mode FROM tenants WHERE id = ?",
            String.class, tenantId);
        return "CREATIVE_TESTING_TOOL".equals(mode);
    }

    /**
     * In CREATIVE_TESTING_TOOL mode: equal-split launch where every ad set
     * receives identical budget and is marked as an exploration slot.
     * Returns same response shape as launchTestBatch for API compatibility.
     */
    @Transactional
    public List<AdSetResponse> launchCreativeTestBatch(UUID tenantId, TestBatchRequest req) {
        if (!isCreativeTestingToolMode(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant is not in CREATIVE_TESTING_TOOL mode. Use launchTestBatch instead.");
        }
        MetaCampaign campaign = requireCampaign(tenantId, req.getCampaignId());
        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();
        if (adAccountId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant has no Meta ad account ID");
        }

        int totalAdSets = req.getAdSets().size();
        if (totalAdSets < 2) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "CREATIVE_TESTING_TOOL mode requires at least 2 ad sets");
        }

        // Strictly equal budget across all slots — no anchor exception in this mode
        float totalBudget = req.getPerAdSetDailyBudget() * totalAdSets;
        float perSlotBudget = Math.round((totalBudget / totalAdSets) * 100) / 100f;

        UUID batchId = UUID.randomUUID();
        Instant now = Instant.now();
        List<AdSetResponse> created = new ArrayList<>();

        for (var adSetReq : req.getAdSets()) {
            String name = adSetReq.getName() != null ? adSetReq.getName()
                    : "ctt-" + batchId.toString().substring(0, 8) + "-" + (created.size() + 1);
            String metaAdSetId = metaApi.createAdSet(
                    token, adAccountId, campaign.getMetaCampaignId(),
                    name, adSetReq.getAudienceSpec(), perSlotBudget);

            MetaAdSet adSet = new MetaAdSet();
            adSet.setTenantId(tenantId);
            adSet.setCampaignId(campaign.getId());
            adSet.setMetaAdSetId(metaAdSetId);
            adSet.setCreativeId(adSetReq.getCreativeId());
            adSet.setAudienceSpec(adSetReq.getAudienceSpec());
            adSet.setDailyBudget(perSlotBudget);
            adSet.setStatus("PAUSED");
            adSet.setBatchId(batchId);
            adSet.setBatchLaunchedAt(now);
            adSet.setExplorationSlot(true); // all slots are exploration in CTT mode
            adSet.setAnchor(false);

            learningPhaseService.logBeforeAction(tenantId, metaAdSetId, "LAUNCH",
                    adSet.getLearningPhase());
            created.add(campaignService.toAdSetResponse(adSetRepo.save(adSet)));
        }

        log.info("CREATIVE_TESTING_TOOL batch {} launched: {} slots × {} budget (tenant {})",
                batchId, totalAdSets, perSlotBudget, tenantId);
        return created;
    }

    // ─── Budget Guard (§6.5: ≤20% per step) ─────────────────────────────

    /**
     * Validates that a budget change does not exceed 20% in one step.
     * A change > 20% can reset Meta's Learning Phase.
     */
    public static void validateBudgetStep(Float currentBudget, Float newBudget, String entityId) {
        if (currentBudget == null || currentBudget == 0) return;
        // Compute as double to avoid float→double widening errors (0.2f widens to 0.200000003...)
        double changePct = Math.abs(newBudget.doubleValue() - currentBudget.doubleValue()) / currentBudget.doubleValue();
        if (changePct > 0.20 + 1e-7) {  // 1e-7 tolerance for floating-point representation
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.format("Budget change of %.0f%% exceeds the ≤20%% rule (§6.5) for entity %s. "
                            + "Use incremental steps to avoid resetting Meta's Learning Phase.",
                            changePct * 100, entityId));
        }
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private MetaCampaign requireCampaign(UUID tenantId, UUID campaignId) {
        MetaCampaign c = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!c.getTenantId().equals(tenantId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return c;
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private String decodeToken(Tenant tenant) {
        if (tenant.getMetaAccessTokenEnc() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tenant has no Meta access token");
        }
        return new String(tenant.getMetaAccessTokenEnc(), StandardCharsets.UTF_8);
    }

    private String buildRecommendation(AdSetScore winner, double groupMean) {
        double lift = winner.shrunk / groupMean - 1;
        if (winner.observations < 50) {
            return String.format("Winner has limited data (%d obs). "
                    + "Consider running longer before confirmation run. "
                    + "Shrunk estimate: %.1f%% (raw: %.1f%%)",
                    winner.observations, winner.shrunk * 100, winner.raw * 100);
        }
        return String.format("Winner shows %.0f%% lift over group mean (shrunk estimate). "
                + "Ready for confirmation run.",
                lift * 100);
    }

    private static class AdSetScore {
        final MetaAdSet adSet;
        final double raw;
        final long observations;
        double shrunk;

        AdSetScore(MetaAdSet adSet, double raw, long observations) {
            this.adSet = adSet;
            this.raw = raw;
            this.observations = observations;
            this.shrunk = raw;
        }
    }
}
