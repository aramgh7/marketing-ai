package com.marketingai.repository;

import com.marketingai.entity.CampaignCpmHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CampaignCpmHistoryRepository extends JpaRepository<CampaignCpmHistory, UUID> {

    /** Last 14 days of CPM history for a campaign (for z-score spike detection) */
    List<CampaignCpmHistory> findByCampaignIdAndRecordedAtAfterOrderByRecordedAtDesc(
            UUID campaignId, Instant since);
}
