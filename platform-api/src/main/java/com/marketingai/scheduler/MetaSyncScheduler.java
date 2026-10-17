package com.marketingai.scheduler;

import com.marketingai.entity.MetaAdSet;
import com.marketingai.entity.MetaCampaign;
import com.marketingai.entity.Tenant;
import com.marketingai.repository.MetaAdSetRepository;
import com.marketingai.repository.MetaCampaignRepository;
import com.marketingai.repository.TenantRepository;
import com.marketingai.service.MetaApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pulls spend + learning_phase from Meta every 30 minutes for all ACTIVE campaigns.
 * Updates local spend_today and spend_total columns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetaSyncScheduler {

    private final MetaCampaignRepository campaignRepo;
    private final MetaAdSetRepository adSetRepo;
    private final MetaApiService metaApi;
    private final TenantRepository tenantRepo;

    @Scheduled(fixedDelay = 30 * 60 * 1000, initialDelay = 60_000)
    public void syncAll() {
        log.info("MetaSyncScheduler: starting 30-min Meta sync");
        int synced = 0;

        for (MetaCampaign campaign : campaignRepo.findAllByStatus("ACTIVE")) {
            try {
                Optional<Tenant> tenantOpt = tenantRepo.findById(campaign.getTenantId());
                if (tenantOpt.isEmpty() || tenantOpt.get().getMetaAccessTokenEnc() == null) continue;

                String token = new String(tenantOpt.get().getMetaAccessTokenEnc(), StandardCharsets.UTF_8);

                Map<String, String> insights = metaApi.getInsights(token, campaign.getMetaCampaignId());
                float spend = Float.parseFloat(insights.getOrDefault("spend", "0"));
                campaign.setSpendToday(spend);
                campaign.setSpendTotal((campaign.getSpendTotal() == null ? 0f : campaign.getSpendTotal()) + spend);
                campaign.setSyncedAt(Instant.now());
                campaignRepo.save(campaign);
                synced++;

                syncAdSets(token, campaign.getId());
            } catch (Exception e) {
                log.warn("MetaSyncScheduler: failed to sync campaign {}: {}",
                        campaign.getId(), e.getMessage());
            }
        }

        log.info("MetaSyncScheduler: synced {} campaigns", synced);
    }

    private void syncAdSets(String token, UUID campaignId) {
        for (MetaAdSet adSet : adSetRepo.findAllByCampaignIdAndStatus(campaignId, "ACTIVE")) {
            try {
                Map<String, String> insights = metaApi.getInsights(token, adSet.getMetaAdSetId());
                float spend = Float.parseFloat(insights.getOrDefault("spend", "0"));
                long impressions = Long.parseLong(insights.getOrDefault("impressions", "0"));
                long clicks = Long.parseLong(insights.getOrDefault("clicks", "0"));
                long conversions = Long.parseLong(insights.getOrDefault("conversions", "0"));

                adSet.setSpendToday(spend);
                adSet.setSpendTotal((adSet.getSpendTotal() == null ? 0f : adSet.getSpendTotal()) + spend);
                // Accumulate totals (today's delta added each sync cycle)
                adSet.setImpressionsTotal((adSet.getImpressionsTotal() == null ? 0L : adSet.getImpressionsTotal()) + impressions);
                adSet.setClicksTotal((adSet.getClicksTotal() == null ? 0L : adSet.getClicksTotal()) + clicks);
                adSet.setConversionsTotal((adSet.getConversionsTotal() == null ? 0L : adSet.getConversionsTotal()) + conversions);
                adSet.setSyncedAt(Instant.now());
                adSetRepo.save(adSet);
            } catch (Exception e) {
                log.warn("MetaSyncScheduler: failed to sync ad set {}: {}", adSet.getId(), e.getMessage());
            }
        }
    }
}
