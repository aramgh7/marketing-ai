package com.marketingai.service;

import com.marketingai.dto.meta.AdSetRequest;
import com.marketingai.dto.meta.AdSetResponse;
import com.marketingai.dto.meta.CampaignRequest;
import com.marketingai.dto.meta.CampaignResponse;
import com.marketingai.entity.MetaAdSet;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.MetaAdSetRepository;
import com.marketingai.repository.MetaCampaignRepository;
import com.marketingai.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetaCampaignService {

    private final MetaCampaignRepository campaignRepo;
    private final MetaAdSetRepository adSetRepo;
    private final MetaApiService metaApi;
    private final TenantRepository tenantRepo;

    // ─── Campaigns ──────────────────────────────────────────────────────────

    @Transactional
    public CampaignResponse createCampaign(UUID tenantId, CampaignRequest req) {
        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();
        if (adAccountId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant has no Meta ad account ID configured");
        }

        String metaCampaignId = metaApi.createCampaign(
                token, adAccountId, req.getName(), req.getObjective(), req.getDailyBudget());

        MetaCampaign campaign = new MetaCampaign();
        campaign.setTenantId(tenantId);
        campaign.setMetaCampaignId(metaCampaignId);
        campaign.setName(req.getName());
        campaign.setObjective(req.getObjective());
        campaign.setContour(req.getContour() != null ? req.getContour() : "TEST");
        campaign.setDailyBudget(req.getDailyBudget());
        campaign.setStatus("PAUSED");

        return toResponse(campaignRepo.save(campaign));
    }

    public List<CampaignResponse> listCampaigns(UUID tenantId) {
        return campaignRepo.findAllByTenantId(tenantId)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public CampaignResponse pauseCampaign(UUID tenantId, UUID campaignId) {
        return setStatus(tenantId, campaignId, "PAUSED");
    }

    @Transactional
    public CampaignResponse activateCampaign(UUID tenantId, UUID campaignId) {
        return setStatus(tenantId, campaignId, "ACTIVE");
    }

    /**
     * Transfer winner from TEST contour to SCALE contour.
     * Creates a SCALE campaign if one doesn't exist, then migrates the winning ad set.
     */
    @Transactional
    public CampaignResponse transferToScale(UUID tenantId, UUID winnerAdSetId) {
        MetaAdSet winner = adSetRepo.findById(winnerAdSetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ad set not found"));

        if (!winner.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();

        // Find or create SCALE campaign
        MetaCampaign scale = campaignRepo.findByTenantIdAndContour(tenantId, "SCALE")
                .orElseGet(() -> {
                    String metaId = metaApi.createCampaign(
                            token, adAccountId, "SCALE Campaign", "CONVERSIONS", null);
                    MetaCampaign c = new MetaCampaign();
                    c.setTenantId(tenantId);
                    c.setMetaCampaignId(metaId);
                    c.setName("SCALE Campaign");
                    c.setObjective("CONVERSIONS");
                    c.setContour("SCALE");
                    c.setStatus("PAUSED");
                    return campaignRepo.save(c);
                });

        // Move winner ad set to SCALE campaign
        winner.setCampaignId(scale.getId());
        adSetRepo.save(winner);

        log.info("Transferred ad set {} to SCALE campaign {} for tenant {}",
                winnerAdSetId, scale.getId(), tenantId);
        return toResponse(scale);
    }

    // ─── Ad Sets ────────────────────────────────────────────────────────────

    @Transactional
    public AdSetResponse createAdSet(UUID tenantId, AdSetRequest req) {
        if (req.getCampaignId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaignId is required");
        }
        MetaCampaign campaign = campaignRepo.findById(req.getCampaignId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!campaign.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Tenant tenant = requireTenant(tenantId);
        String token = decodeToken(tenant);
        String adAccountId = tenant.getMetaAdAccountId();

        String metaAdSetId = metaApi.createAdSet(
                token, adAccountId, campaign.getMetaCampaignId(),
                req.getName(), req.getAudienceSpec(), req.getDailyBudget());

        MetaAdSet adSet = new MetaAdSet();
        adSet.setTenantId(tenantId);
        adSet.setCampaignId(campaign.getId());
        adSet.setMetaAdSetId(metaAdSetId);
        adSet.setCreativeId(req.getCreativeId());
        adSet.setAudienceSpec(req.getAudienceSpec());
        adSet.setDailyBudget(req.getDailyBudget());
        adSet.setStatus("PAUSED");

        return toAdSetResponse(adSetRepo.save(adSet));
    }

    public List<AdSetResponse> listAdSets(UUID tenantId, UUID campaignId) {
        return adSetRepo.findAllByCampaignId(campaignId)
                .stream()
                .filter(a -> a.getTenantId().equals(tenantId))
                .map(this::toAdSetResponse)
                .toList();
    }

    // ─── Internal ───────────────────────────────────────────────────────────

    private CampaignResponse setStatus(UUID tenantId, UUID campaignId, String status) {
        MetaCampaign campaign = campaignRepo.findById(campaignId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
        if (!campaign.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        Tenant tenant = requireTenant(tenantId);
        metaApi.setCampaignStatus(decodeToken(tenant), campaign.getMetaCampaignId(), status);
        campaign.setStatus(status);
        return toResponse(campaignRepo.save(campaign));
    }

    private Tenant requireTenant(UUID tenantId) {
        return tenantRepo.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private String decodeToken(Tenant tenant) {
        if (tenant.getMetaAccessTokenEnc() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tenant has no Meta access token configured");
        }
        return new String(tenant.getMetaAccessTokenEnc(), StandardCharsets.UTF_8);
    }

    CampaignResponse toResponse(MetaCampaign c) {
        return CampaignResponse.builder()
                .id(c.getId())
                .tenantId(c.getTenantId())
                .metaCampaignId(c.getMetaCampaignId())
                .name(c.getName())
                .objective(c.getObjective())
                .contour(c.getContour())
                .dailyBudget(c.getDailyBudget())
                .status(c.getStatus())
                .learningPhase(c.getLearningPhase())
                .spendToday(c.getSpendToday())
                .spendTotal(c.getSpendTotal())
                .confirmationAdSetId(c.getConfirmationAdSetId())
                .confirmationStatus(c.getConfirmationStatus())
                .syncedAt(c.getSyncedAt())
                .createdAt(c.getCreatedAt())
                .build();
    }

    AdSetResponse toAdSetResponse(MetaAdSet a) {
        return AdSetResponse.builder()
                .id(a.getId())
                .campaignId(a.getCampaignId())
                .metaAdSetId(a.getMetaAdSetId())
                .metaAdId(a.getMetaAdId())
                .creativeId(a.getCreativeId())
                .audienceSpec(a.getAudienceSpec())
                .dailyBudget(a.getDailyBudget())
                .status(a.getStatus())
                .learningPhase(a.getLearningPhase())
                .spendToday(a.getSpendToday())
                .spendTotal(a.getSpendTotal())
                .batchId(a.getBatchId())
                .batchLaunchedAt(a.getBatchLaunchedAt())
                .isExplorationSlot(a.isExplorationSlot())
                .syncedAt(a.getSyncedAt())
                .createdAt(a.getCreatedAt())
                .build();
    }
}
