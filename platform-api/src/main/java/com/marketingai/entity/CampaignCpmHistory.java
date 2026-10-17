package com.marketingai.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "campaign_cpm_history")
@Getter
@Setter
@NoArgsConstructor
public class CampaignCpmHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID campaignId;

    @Column(nullable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private Float cpm;
}
