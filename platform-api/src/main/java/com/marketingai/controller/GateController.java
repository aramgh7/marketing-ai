package com.marketingai.controller;

import com.marketingai.dto.meta.GateStatusResponse;
import com.marketingai.dto.meta.GateSummaryResponse;
import com.marketingai.entity.MetaAdSet;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.repository.MetaAdSetRepository;
import com.marketingai.repository.MetaCampaignRepository;
import com.marketingai.security.TenantContext;
import com.marketingai.service.GateEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Gate status visibility and manual evaluation trigger.
 * Automatic gate evaluation runs hourly via GateScanScheduler.
 * Vision §12.3: marketer sees gate status, can trigger manually.
 */
@RestController
@RequiredArgsConstructor
public class GateController {

    private final GateEvaluationService gateService;
    private final MetaCampaignRepository campaignRepo;
    private final MetaAdSetRepository adSetRepo;

    /**
     * Manual gate evaluation for a specific campaign.
     * Returns number of ad sets whose status changed.
     */
    @PostMapping("/campaigns/{id}/evaluate-gates")
    public int evaluateGates(@PathVariable UUID id) {
        MetaCampaign campaign = requireCampaign(id);
        return gateService.evaluateCampaign(campaign);
    }

    /**
     * Gate summary for a campaign: counts by gate status + per-adset details.
     */
    @GetMapping("/campaigns/{id}/gate-summary")
    public GateSummaryResponse gateSummary(@PathVariable UUID id) {
        MetaCampaign campaign = requireCampaign(id);

        List<MetaAdSet> adSets = adSetRepo.findAllByCampaignId(campaign.getId())
                .stream()
                .filter(a -> a.getTenantId().equals(currentTenant()))
                .toList();

        List<GateStatusResponse> details = adSets.stream().map(this::toGateStatus).toList();

        return GateSummaryResponse.builder()
                .campaignId(id)
                .total(adSets.size())
                .atGate1((int) adSets.stream().filter(a -> "GATE_1".equals(a.getGateStatus())).count())
                .atGate2((int) adSets.stream().filter(a -> "GATE_2".equals(a.getGateStatus())).count())
                .atGate3((int) adSets.stream().filter(a -> "GATE_3".equals(a.getGateStatus())).count())
                .passed((int) adSets.stream().filter(a -> "PASSED".equals(a.getGateStatus())).count())
                .killed((int) adSets.stream().filter(a -> "KILLED".equals(a.getGateStatus())).count())
                .adSets(details)
                .build();
    }

    /**
     * Gate status for a single ad set.
     */
    @GetMapping("/ad-sets/{id}/gate-status")
    public GateStatusResponse adSetGateStatus(@PathVariable UUID id) {
        MetaAdSet adSet = adSetRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ad set not found"));
        if (!adSet.getTenantId().equals(currentTenant())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return toGateStatus(adSet);
    }

    /**
     * Mark an ad set as anchor creative for Gate 3 comparison.
     * The anchor is the known-good control creative (vision §12.3).
     */
    @PutMapping("/ad-sets/{id}/anchor")
    public GateStatusResponse setAnchor(@PathVariable UUID id, @RequestParam boolean anchor) {
        MetaAdSet adSet = adSetRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ad set not found"));
        if (!adSet.getTenantId().equals(currentTenant())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        // Only one anchor per campaign
        if (anchor) {
            adSetRepo.findAllByCampaignId(adSet.getCampaignId())
                    .forEach(a -> {
                        if (a.isAnchor()) {
                            a.setAnchor(false);
                            adSetRepo.save(a);
                        }
                    });
        }

        adSet.setAnchor(anchor);
        return toGateStatus(adSetRepo.save(adSet));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private MetaCampaign requireCampaign(UUID id) {
        MetaCampaign c = campaignRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!c.getTenantId().equals(currentTenant())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return c;
    }

    private UUID currentTenant() {
        String raw = TenantContext.get();
        if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context");
        return UUID.fromString(raw);
    }

    private GateStatusResponse toGateStatus(MetaAdSet a) {
        return GateStatusResponse.builder()
                .adSetId(a.getId())
                .metaAdSetId(a.getMetaAdSetId())
                .creativeId(a.getCreativeId())
                .gateStatus(a.getGateStatus())
                .killReason(a.getGateKillReason())
                .killAt(a.getGateKillAt())
                .impressionsTotal(a.getImpressionsTotal() != null ? a.getImpressionsTotal() : 0L)
                .clicksTotal(a.getClicksTotal() != null ? a.getClicksTotal() : 0L)
                .conversionsTotal(a.getConversionsTotal() != null ? a.getConversionsTotal() : 0L)
                .spendTotal(a.getSpendTotal() != null ? a.getSpendTotal() : 0f)
                .isAnchor(a.isAnchor())
                .gate1EvaluatedAt(a.getGate1EvaluatedAt())
                .gate2EvaluatedAt(a.getGate2EvaluatedAt())
                .gate3EvaluatedAt(a.getGate3EvaluatedAt())
                .batchLaunchedAt(a.getBatchLaunchedAt())
                .build();
    }
}
