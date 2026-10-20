package com.marketingai.scheduler;

import com.marketingai.service.MlServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hourly negative-outcome sweep for the Thompson bandit (audit 2.3).
 *
 * A decision with no reward inside the attribution window is one Bernoulli failure:
 * FastAPI increments beta for the chosen arm and logs NO_RESPONSE_7D in thompson_reward_log
 * (idempotent; a late positive reward reverses it). Without this sweep beta never moved and
 * Beta(alpha, beta) degenerated into a raw success count.
 *
 * FastAPI remains the sole writer of thompson_arms — Spring only triggers the sweep.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RewardSweepScheduler {

    private final MlServiceClient mlClient;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 300_000)
    public void sweep() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mlClient.post("/decide/reward/sweep-negatives",
                    Map.of("window_days", 7, "max_age_days", 30, "limit", 2000), Map.class);
            log.info("Thompson negative sweep: {}", result);
        } catch (Exception e) {
            log.warn("Thompson negative sweep failed: {}", e.getMessage());
        }
    }
}
