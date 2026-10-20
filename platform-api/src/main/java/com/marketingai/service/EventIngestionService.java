package com.marketingai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketingai.dto.event.EventBatchResponse;
import com.marketingai.dto.event.EventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventIngestionService {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final FraudScoringService fraudService;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Drop these event types when consent = analytics_only or none. */
    private static final Set<String> BEHAVIORAL = Set.of(
            "SCROLL", "HOVER", "BLOCK_VIEW", "CLICK", "VIDEO_PROGRESS", "FIRST_CLICK",
            "QUIZ_ANSWER", "CALC_INTERACT", "RETURN_VISIT");  // mirrors frontend/src/lib/tracker.ts (audit 7.8)

    /** These are allowed even with consent = none. */
    private static final Set<String> CONVERSION = Set.of(
            "FORM_SUBMIT", "CONVERSION", "PURCHASE", "LEAD"
    );

    // Requests from SiteHealthScheduler must not create sessions or events
    private static final String SENTINEL_UA = "MarketingAI-HealthCheck/1.0";

    /** Audit §3.4: only CRM/webhook/server paths may record these; the browser cannot. */
    static final Set<String> SERVER_ONLY_TYPES = Set.of(
            "CONVERSION", "PURCHASE", "LEAD", "IMPRESSION",
            "CRM_CALL_ANSWERED", "CRM_MEETING_ATTENDED", "CRM_NO_SHOW", "CRM_DEAL_WON", "CRM_DEAL_LOST");

    public EventBatchResponse ingest(List<EventDto> events,
                                      String ipAddress,
                                      String userAgent,
                                      Integer cfBotScore) {
        if (SENTINEL_UA.equals(userAgent)) {
            return new EventBatchResponse(0, events.size(), "sentinel");
        }

        int accepted = 0;
        int skipped  = 0;

        List<EventDto> toWrite = new ArrayList<>(events.size());

        // Audit §3.4: tenant_id/variant_id/creative_id/revenue used to be trusted verbatim from the
        // tracker payload. Resolve ownership server-side once per batch and reject mismatches.
        Map<UUID, UUID> variantTenants  = lookupOwners("landing_variants", events.stream().map(EventDto::getVariantId).toList());
        Map<UUID, UUID> creativeTenants = lookupOwners("creatives", events.stream().map(EventDto::getCreativeId).toList());
        Set<UUID> knownTenants = lookupTenants(events.stream().map(EventDto::getTenantId).toList());

        for (EventDto e : events) {
            String reject = rejectReason(e, variantTenants, creativeTenants, knownTenants);
            if (reject != null) {
                log.debug("event rejected ({}): tenant={} variant={} type={}", reject, e.getTenantId(), e.getVariantId(), e.getEventType());
                skipped++;
                continue;
            }
            sanitize(e);
            if (!allowedByConsent(e)) { skipped++; continue; }
            if (isDuplicate(e))        { skipped++; continue; }
            markSeen(e);
            toWrite.add(e);
            accepted++;
        }

        if (!toWrite.isEmpty()) {
            upsertSessions(toWrite, ipAddress, userAgent);
            writeEvents(toWrite);
            fraudService.scoreInline(toWrite, ipAddress, userAgent, cfBotScore);
        }

        return new EventBatchResponse(accepted, skipped, "ok");
    }

    // ─── Server-side ownership validation ────────────────────────────────────

    /** Null = accepted; otherwise a short reason. Pure, unit-tested. */
    static String rejectReason(EventDto e, Map<UUID, UUID> variantTenants,
                               Map<UUID, UUID> creativeTenants, Set<UUID> knownTenants) {
        if (e.getTenantId() == null) return "missing_tenant";
        if (e.getEventType() != null && SERVER_ONLY_TYPES.contains(e.getEventType())) return "server_only_event_type";
        if (e.getVariantId() != null) {
            UUID owner = variantTenants.get(e.getVariantId());
            if (owner == null) return "unknown_variant";
            return owner.equals(e.getTenantId()) ? null : "variant_tenant_mismatch";
        }
        if (e.getCreativeId() != null) {
            UUID owner = creativeTenants.get(e.getCreativeId());
            if (owner == null) return "unknown_creative";
            return owner.equals(e.getTenantId()) ? null : "creative_tenant_mismatch";
        }
        return knownTenants.contains(e.getTenantId()) ? null : "unknown_tenant";
    }

    /** Client-supplied monetary values are never trusted; revenue comes from the CRM webhook. */
    static void sanitize(EventDto e) {
        e.setRevenue(null);
    }

    private Map<UUID, UUID> lookupOwners(String table, List<UUID> ids) {
        List<UUID> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Map.of();
        Map<UUID, UUID> out = new HashMap<>();
        jdbc.query("SELECT id, tenant_id FROM " + table + " WHERE id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", distinct.toArray())),
                rs -> { out.put((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id")); });
        return out;
    }

    private Set<UUID> lookupTenants(List<UUID> ids) {
        List<UUID> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) return Set.of();
        Set<UUID> out = new HashSet<>();
        jdbc.query("SELECT id FROM tenants WHERE id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", distinct.toArray())),
                rs -> { out.add((UUID) rs.getObject("id")); });
        return out;
    }

    // ─── Consent filter ──────────────────────────────────────────────────────

    private boolean allowedByConsent(EventDto e) {
        String c = e.getConsentLevel();
        if ("none".equals(c)) {
            return CONVERSION.contains(e.getEventType());
        }
        if ("analytics_only".equals(c)) {
            return !BEHAVIORAL.contains(e.getEventType());
        }
        return true; // "full" or null → accept all
    }

    // ─── Deduplication (Redis, 24 h TTL) ─────────────────────────────────────

    private boolean isDuplicate(EventDto e) {
        if (e.getEventId() == null || e.getTenantId() == null) return false;
        return Boolean.TRUE.equals(redis.hasKey(dedupKey(e)));
    }

    private void markSeen(EventDto e) {
        if (e.getEventId() == null || e.getTenantId() == null) return;
        redis.opsForValue().set(dedupKey(e), "1", Duration.ofHours(24));
    }

    private String dedupKey(EventDto e) {
        return "dedup:" + e.getTenantId() + ":" + e.getEventId();
    }

    // ─── Session upsert ───────────────────────────────────────────────────────

    private void upsertSessions(List<EventDto> events, String ipAddress, String userAgent) {
        Map<String, EventDto> first = new LinkedHashMap<>();
        for (EventDto e : events) {
            first.putIfAbsent(e.getSessionId(), e);
        }

        Map<String, EventDto> conversion = new HashMap<>();
        for (EventDto e : events) {
            if (CONVERSION.contains(e.getEventType())) {
                conversion.put(e.getSessionId(), e);
            }
        }

        Instant now = Instant.now();
        String sql = """
                INSERT INTO sessions (
                    id, tenant_id, creative_id, variant_id, device, geo,
                    utm_source, utm_medium, utm_campaign, consent_level,
                    ip_address, user_agent,
                    started_at, last_event_at, converted, conversion_type, revenue
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    last_event_at   = EXCLUDED.last_event_at,
                    converted       = CASE WHEN sessions.converted THEN true ELSE EXCLUDED.converted END,
                    conversion_type = COALESCE(sessions.conversion_type, EXCLUDED.conversion_type),
                    revenue         = COALESCE(sessions.revenue, EXCLUDED.revenue)
                """;

        for (Map.Entry<String, EventDto> entry : first.entrySet()) {
            EventDto e    = entry.getValue();
            EventDto conv = conversion.get(e.getSessionId());

            jdbc.update(sql,
                    e.getSessionId(),
                    e.getTenantId(),
                    e.getCreativeId(),
                    e.getVariantId(),
                    e.getDevice(),
                    e.getGeo(),
                    e.getUtmSource(),
                    e.getUtmMedium(),
                    e.getUtmCampaign(),
                    e.getConsentLevel() != null ? e.getConsentLevel() : "full",
                    ipAddress,
                    userAgent,
                    Timestamp.from(now),
                    Timestamp.from(now),
                    conv != null,
                    conv != null ? conv.getEventType() : null,
                    conv != null ? conv.getRevenue() : null
            );
        }
    }

    // ─── Batch event write ────────────────────────────────────────────────────

    private void writeEvents(List<EventDto> events) {
        String sql = """
                INSERT INTO events (
                    id, tenant_id, session_id, event_type,
                    creative_id, variant_id, block_event_id,
                    event_data, revenue, device, geo,
                    utm_source, utm_medium, utm_campaign,
                    consent_level, is_suspicious, event_time, received_at, event_id
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?,
                    ?, ?, ?,
                    ?::jsonb, ?, ?, ?,
                    ?, ?, ?,
                    ?, false, ?, now(), ?
                )
                """;

        Instant now = Instant.now();
        jdbc.batchUpdate(sql, events, events.size(), (ps, e) -> {
            ps.setObject(1, e.getTenantId());
            ps.setString(2, e.getSessionId());
            ps.setString(3, e.getEventType());
            ps.setObject(4, e.getCreativeId());
            ps.setObject(5, e.getVariantId());
            ps.setString(6, e.getBlockEventId());
            ps.setString(7, toJson(e.getEventData()));
            ps.setObject(8, e.getRevenue());
            ps.setString(9, e.getDevice());
            ps.setString(10, e.getGeo());
            ps.setString(11, e.getUtmSource());
            ps.setString(12, e.getUtmMedium());
            ps.setString(13, e.getUtmCampaign());
            ps.setString(14, e.getConsentLevel() != null ? e.getConsentLevel() : "full");
            ps.setTimestamp(15, Timestamp.from(
                    e.getEventTime() != null ? e.getEventTime() : now));
            ps.setString(16, e.getEventId());
        });
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String toJson(Map<String, Object> data) {
        if (data == null) return "{}";
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
