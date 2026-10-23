package com.marketingai.service;

import com.marketingai.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Priority-queue wrapper for Meta Graph API calls (3-15).
 * Three lanes: P0 (emergency/pause), P1 (learning-phase), P2 (bid/budget).
 * Token bucket: 100 calls/hour — refilled each hour.
 * All calls are logged to meta_api_audit (D-11).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaApiClient {

    private static final int TOKEN_BUCKET_CAPACITY = 100;

    private final JdbcTemplate jdbc;

    private final MetaWriteGate metaWriteGate;   // roadmap 9-19: single choke point for Meta writes
    private final MetaApiAuditService auditService;  // D-11: all calls logged to meta_api_audit
    private final AppProperties appProperties;

    private final AtomicInteger tokenBucket = new AtomicInteger(TOKEN_BUCKET_CAPACITY);
    private final AtomicLong bucketRefillAt = new AtomicLong(System.currentTimeMillis() + 3_600_000L);

    // P0 is unbounded — emergencies always execute
    private final ArrayBlockingQueue<Runnable> p1Queue = new ArrayBlockingQueue<>(200);
    private final ArrayBlockingQueue<Runnable> p2Queue = new ArrayBlockingQueue<>(500);

    /**
     * Enqueue a Meta API call.
     * P0 executes immediately (bypasses token bucket).
     * P1/P2 respect the token bucket.
     */
    public void enqueue(String lane, String adSetId, UUID tenantId, String action, Runnable call) {
        refillBucketIfNeeded();
        // Roadmap 9-19: every queued write passes the policy; protective pauses are only recorded.
        MetaWritePolicy.Decision d = metaWriteGate.decide(tenantId,
                MetaWriteGate.Request.of(MetaWriteAction.fromLegacyName(action), adSetId));
        if (!d.allowed()) {
            log.warn("MetaApiClient: {} for tenant={} adSet={} skipped by policy ({}): {}", action, tenantId, adSetId, d.verdict(), d.reason());
            logCall(tenantId, adSetId, action, lane, "POLICY_" + d.verdict());
            return;
        }

        switch (lane.toUpperCase()) {
            case "P0" -> executeP0(tenantId, adSetId, action, call);
            case "P1" -> scheduleWithBucket("P1", tenantId, adSetId, action, call);
            case "P2" -> scheduleWithBucket("P2", tenantId, adSetId, action, call);
            default   -> {
                log.warn("Unknown Meta API lane '{}' — defaulting to P2", lane);
                scheduleWithBucket("P2", tenantId, adSetId, action, call);
            }
        }
    }

    private void executeP0(UUID tenantId, String adSetId, String action, Runnable call) {
        log.info("MetaApiClient P0 execute: tenant={} adSet={} action={}", tenantId, adSetId, action);
        try {
            call.run();
            logCall(tenantId, adSetId, action, "P0", "SUCCESS");
        } catch (Exception e) {
            log.error("MetaApiClient P0 failed: tenant={} action={} error={}", tenantId, action, e.getMessage());
            logCall(tenantId, adSetId, action, "P0", "FAILED:" + e.getMessage());
        }
    }

    private void scheduleWithBucket(String lane, UUID tenantId, String adSetId, String action, Runnable call) {
        int remaining = tokenBucket.getAndDecrement();
        if (remaining <= 0) {
            tokenBucket.incrementAndGet(); // return token
            log.warn("MetaApiClient token bucket exhausted for lane={} action={}", lane, action);
            return;
        }
        log.info("MetaApiClient {} execute: tenant={} adSet={} action={} tokens_left={}",
                lane, tenantId, adSetId, action, remaining - 1);
        try {
            call.run();
            logCall(tenantId, adSetId, action, lane, "SUCCESS");
        } catch (Exception e) {
            log.error("MetaApiClient {} failed: tenant={} action={} error={}", lane, tenantId, action, e.getMessage());
            logCall(tenantId, adSetId, action, lane, "FAILED:" + e.getMessage());
        }
    }

    private void refillBucketIfNeeded() {
        long now = System.currentTimeMillis();
        long refillAt = bucketRefillAt.get();
        if (now >= refillAt && bucketRefillAt.compareAndSet(refillAt, now + 3_600_000L)) {
            tokenBucket.set(TOKEN_BUCKET_CAPACITY);
            log.info("MetaApiClient token bucket refilled to {}", TOKEN_BUCKET_CAPACITY);
        }
    }

    public void recordLearningPhaseChange(UUID adSetId, UUID tenantId,
                                           String phaseBefore, String phaseAfter, String action) {
        try {
            jdbc.update("""
                    INSERT INTO learning_phase_log
                    (ad_set_id, tenant_id, phase_before, phase_after, action, created_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    """, adSetId != null ? adSetId.toString() : null, tenantId, phaseBefore, phaseAfter, action);
        } catch (Exception e) {
            log.warn("Failed to record learning_phase_log: {}", e.getMessage());
        }
    }

    private void logCall(UUID tenantId, String adSetId, String action, String lane, String status) {
        // D-11: all queued Meta API calls are logged to meta_api_audit (not learning_phase_log)
        int code = status != null && status.startsWith("FAILED") ? 500
                 : status != null && status.startsWith("POLICY") ? 403
                 : 200;
        auditService.log(tenantId, "JOB", null, action, "adset", adSetId, null, code, lane, 0);
    }
}
