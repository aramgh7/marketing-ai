package com.marketingai.dto.budget;

import com.marketingai.entity.BudgetGuard;
import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class BudgetGuardResponse {

    private UUID id;
    private String guardType;
    private UUID referenceId;
    private float threshold;
    private float currentSpend;
    private boolean triggered;
    private Instant triggeredAt;
    private LocalDate periodStart;
    private Instant createdAt;

    public static BudgetGuardResponse from(BudgetGuard g) {
        BudgetGuardResponse r = new BudgetGuardResponse();
        r.id = g.getId();
        r.guardType = g.getGuardType();
        r.referenceId = g.getReferenceId();
        r.threshold = g.getThreshold();
        r.currentSpend = g.getCurrentSpend();
        r.triggered = g.isTriggered();
        r.triggeredAt = g.getTriggeredAt();
        r.periodStart = g.getPeriodStart();
        r.createdAt = g.getCreatedAt();
        return r;
    }
}
