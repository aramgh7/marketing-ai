package com.marketingai.service;

import com.marketingai.dto.meta.RecommendedBatchResponse;
import com.marketingai.dto.meta.RecommendedBatchResponse.CandidateSlot;
import com.marketingai.entity.Creative;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.entity.ThompsonArm;
import com.marketingai.repository.CreativeRepository;
import com.marketingai.repository.MetaCampaignRepository;
import com.marketingai.repository.ThompsonArmRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Exploration Budget — splits test batch into exploitation + exploration slots (§6.4).
 *
 * Exploitation slots (1 - rate):
 *   Ranked by EVI approximated via Top-Two Thompson sampling.
 *   Prioritizes creatives that are: (a) near the top AND (b) still uncertain.
 *   A known winner is less informative than a close competitor — top-two captures this.
 *
 * Exploration slots (rate, default 15%):
 *   Filled by creatives with zero or near-zero observations.
 *   Each new creative receives the optimistic prior Beta(alpha, beta) from the creative entity.
 *   Default Beta(2,1): P(win) ≈ 0.67 at start, wide enough to get exploration traffic.
 *
 * Vision §6.4: "same 10-20% budget, but candidates for experiments are EVI-ranked.
 *   5-6 genuinely useful experiments out of 10 instead of 3 random ones."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExplorationBudgetService {

    private static final int    EVI_SAMPLES      = 5_000;
    private static final long   NEW_CREATIVE_THRESHOLD = 10L; // < 10 obs = "new"

    private final CreativeRepository        creativeRepo;
    private final ThompsonArmRepository     armRepo;
    private final MetaCampaignRepository    campaignRepo;

    // ─── Main API ────────────────────────────────────────────────────────

    /**
     * Recommend a test batch for the given campaign.
     * Returns exploitation + exploration candidates sorted by priority.
     *
     * @param tenantId   tenant
     * @param campaignId campaign whose explorationRate is used
     * @param batchSize  total desired batch size (e.g. 9)
     */
    public RecommendedBatchResponse recommendBatch(UUID tenantId, UUID campaignId, int batchSize) {
        MetaCampaign campaign = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!campaign.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        float rate = campaign.getExplorationRate() != null ? campaign.getExplorationRate() : 0.15f;
        int explorationSlots  = Math.max(1, Math.round(batchSize * rate));
        int exploitationSlots = batchSize - explorationSlots;

        // Load all active creatives for the tenant
        List<Creative> allCreatives = creativeRepo
                .findByTenantIdAndStatusNotOrderByCreatedAtDesc(tenantId, "ARCHIVED");

        // Split by observation count
        Map<UUID, ThompsonArm> armByCreative = loadArms(tenantId, allCreatives);

        List<Creative> newCreatives      = new ArrayList<>();
        List<Creative> testedCreatives   = new ArrayList<>();

        for (Creative c : allCreatives) {
            ThompsonArm arm = armByCreative.get(c.getId());
            long obs = arm != null ? observations(arm) : 0L;
            if (obs < NEW_CREATIVE_THRESHOLD) newCreatives.add(c);
            else testedCreatives.add(c);
        }

        // Exploitation: rank tested creatives by Top-Two EVI
        List<CandidateSlot> exploitation = rankByEvi(testedCreatives, armByCreative, exploitationSlots);

        // Exploration: new creatives with optimistic prior, random order
        Collections.shuffle(newCreatives);
        List<CandidateSlot> exploration = newCreatives.stream()
                .limit(explorationSlots)
                .map(c -> buildExplorationSlot(c, armByCreative.get(c.getId())))
                .toList();

        log.info("Batch recommendation: campaign={} batchSize={} exploitation={} exploration={}",
                campaignId, batchSize, exploitation.size(), exploration.size());

        return RecommendedBatchResponse.builder()
                .campaignId(campaignId)
                .batchSize(batchSize)
                .explorationRate(rate)
                .exploitationSlots(exploitationSlots)
                .explorationSlots(explorationSlots)
                .exploitationCandidates(exploitation)
                .explorationCandidates(exploration)
                .build();
    }

    /**
     * Get the optimistic Thompson prior for a creative (used when seeding a new arm).
     * Returns {alpha, beta} from the creative entity or default Beta(2,1).
     */
    public double[] getOptimisticPrior(Creative creative) {
        double alpha = creative.getExplorationPriorAlpha() != null
                ? creative.getExplorationPriorAlpha() : 2.0;
        double beta  = creative.getExplorationPriorBeta() != null
                ? creative.getExplorationPriorBeta() : 1.0;
        return new double[]{alpha, beta};
    }

    // ─── EVI via Top-Two Thompson ────────────────────────────────────────

    /**
     * Rank creatives by Top-Two Thompson EVI score.
     *
     * Top-Two Thompson (Russo 2016):
     *   In each of N iterations:
     *     1. Sample from every arm's Beta posterior
     *     2. Keep the two highest-scoring arms
     *   EVI score for arm i = fraction of iterations where arm i was in top-2.
     *
     * Intuition: arms near the top with high uncertainty appear in top-2 most often.
     * Arms already clearly dominant OR clearly losing appear less often (less informative).
     * This focuses exploration on the "competition zone" where information has the most value.
     */
    List<CandidateSlot> rankByEvi(List<Creative> creatives,
                                   Map<UUID, ThompsonArm> armByCreative,
                                   int limit) {
        if (creatives.isEmpty()) return List.of();

        Random rng = new Random();
        int n = creatives.size();
        int[] topTwoCount = new int[n];

        for (int iter = 0; iter < EVI_SAMPLES; iter++) {
            double[] samples = new double[n];
            for (int i = 0; i < n; i++) {
                ThompsonArm arm = armByCreative.get(creatives.get(i).getId());
                double alpha = arm != null ? arm.getAlpha() : 2.0;
                double beta  = arm != null ? arm.getBeta()  : 1.0;
                samples[i] = GateEvaluationService.sampleBeta(alpha, beta, rng);
            }

            // Find top-2 indices
            int first = 0, second = -1;
            for (int i = 1; i < n; i++) {
                if (samples[i] > samples[first]) { second = first; first = i; }
                else if (second == -1 || samples[i] > samples[second]) { second = i; }
            }

            topTwoCount[first]++;
            if (second >= 0) topTwoCount[second]++;
        }

        // Build scored list and sort descending
        List<CandidateSlot> scored = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Creative c = creatives.get(i);
            ThompsonArm arm = armByCreative.get(c.getId());
            double evi = (double) topTwoCount[i] / (2.0 * EVI_SAMPLES);
            scored.add(buildExploitationSlot(c, arm, evi));
        }
        scored.sort(Comparator.comparingDouble(CandidateSlot::getEviScore).reversed());

        return scored.stream().limit(limit).toList();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private Map<UUID, ThompsonArm> loadArms(UUID tenantId, List<Creative> creatives) {
        List<UUID> ids = creatives.stream().map(Creative::getId).toList();
        return armRepo.findAllByTenantId(tenantId).stream()
                .filter(arm -> ids.contains(arm.getVariantId()))
                .collect(Collectors.toMap(ThompsonArm::getVariantId, arm -> arm,
                        (a, b) -> a)); // keep first if duplicates
    }

    private CandidateSlot buildExploitationSlot(Creative c, ThompsonArm arm, double eviScore) {
        double alpha = arm != null ? arm.getAlpha() : 2.0;
        double beta  = arm != null ? arm.getBeta()  : 1.0;
        return CandidateSlot.builder()
                .creativeId(c.getId())
                .creativeTitle(c.getTitle())
                .creativeType(c.getType())
                .eviScore(eviScore)
                .priorAlpha(alpha)
                .priorBeta(beta)
                .observations(arm != null ? observations(arm) : 0L)
                .isNew(false)
                .build();
    }

    private CandidateSlot buildExplorationSlot(Creative c, ThompsonArm arm) {
        // New creatives use optimistic prior from the entity (default Beta(2,1))
        double alpha = c.getExplorationPriorAlpha() != null ? c.getExplorationPriorAlpha() : 2.0;
        double beta  = c.getExplorationPriorBeta()  != null ? c.getExplorationPriorBeta()  : 1.0;
        return CandidateSlot.builder()
                .creativeId(c.getId())
                .creativeTitle(c.getTitle())
                .creativeType(c.getType())
                .eviScore(0.0) // not ranked — exploration slot
                .priorAlpha(alpha)
                .priorBeta(beta)
                .observations(arm != null ? observations(arm) : 0L)
                .isNew(true)
                .build();
    }

    private static long observations(ThompsonArm arm) {
        return Math.max(0L, Math.round(arm.getAlpha() + arm.getBeta() - 2.0));
    }
}
