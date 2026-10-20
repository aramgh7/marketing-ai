package com.marketingai.controller;

import com.marketingai.service.TokenVault;
import com.marketingai.service.GoogleAdsApiService;
import com.marketingai.service.TikTokApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Cross-channel account management and insights sync (Phase 7).
 * Manages Google Ads and TikTok Ads channel accounts.
 */
@Slf4j
@RestController
@RequestMapping("/channels")
@RequiredArgsConstructor
public class ChannelController {

    private final JdbcTemplate jdbc;

    private final TokenVault vault;   // roadmap 9-1: sealed secrets
    private final GoogleAdsApiService googleAdsApiService;
    private final TikTokApiService tikTokApiService;

    // ─── GET / — list channel accounts ───────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, channel, account_id, name, status, token_expires_at, settings, created_at
                FROM channel_accounts
                WHERE tenant_id = ?
                ORDER BY channel, created_at
                """, tenantId);

        return ResponseEntity.ok(rows);
    }

    // ─── POST / — add channel account ────────────────────────────────────────

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestAttribute("tenantId") UUID tenantId,
            @RequestBody Map<String, Object> body) {

        String channel   = (String) body.get("channel");
        String accountId = (String) body.get("account_id");
        String name      = (String) body.get("name");
        String settings  = body.getOrDefault("settings", "{}").toString();

        if (channel == null || accountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channel and account_id are required");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO channel_accounts (id, tenant_id, channel, account_id, name, settings)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, id, tenantId, channel.toUpperCase(), accountId, name, settings);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id",         id,
                "channel",    channel.toUpperCase(),
                "account_id", accountId
        ));
    }

    // ─── DELETE /{id} — deactivate ───────────────────────────────────────────

    /** Roadmap 9-1: store the channel OAuth token sealed (AES-GCM) — the only write path for it. */
    @PutMapping("/{id}/token")
    public ResponseEntity<Map<String, Object>> setToken(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String token = body.get("access_token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "access_token required"));
        }
        int n = jdbc.update("UPDATE channel_accounts SET access_token_enc = ? WHERE id = ? AND tenant_id = ?",
                vault.seal(token), id, tenantId);
        return n == 0 ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Map.of("id", id, "status", "token_saved", "sealed", vault.isActive()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        int updated = jdbc.update("""
                UPDATE channel_accounts SET status = 'INACTIVE'
                WHERE id = ? AND tenant_id = ?
                """, id, tenantId);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel account not found");
        }
        return ResponseEntity.noContent().build();
    }

    // ─── GET /{id}/campaigns ─────────────────────────────────────────────────

    @GetMapping("/{id}/campaigns")
    public ResponseEntity<List<Map<String, Object>>> campaigns(
            @RequestAttribute("tenantId") UUID tenantId,
            @PathVariable UUID id) {

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cc.id, cc.channel, cc.external_id, cc.name, cc.status,
                       cc.daily_budget, cc.objective, cc.synced_at
                FROM channel_campaigns cc
                JOIN channel_accounts ca ON ca.id = cc.channel_account_id
                WHERE cc.tenant_id = ? AND ca.id = ?
                ORDER BY cc.created_at DESC
                LIMIT 200
                """, tenantId, id);

        return ResponseEntity.ok(rows);
    }

    // ─── POST /sync — sync insights from all ACTIVE accounts ─────────────────

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync(
            @RequestAttribute("tenantId") UUID tenantId) {

        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT id, channel, account_id,
                       access_token_enc
                FROM channel_accounts
                WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);

        int synced = 0;
        List<String> errors = new ArrayList<>();
        String today = LocalDate.now().toString();
        String weekAgo = LocalDate.now().minusDays(7).toString();

        for (Map<String, Object> account : accounts) {
            String channel     = (String) account.get("channel");
            String accountId   = (String) account.get("account_id");
            String accessToken = vault.openOrNull((byte[]) account.get("access_token_enc"));
            UUID accountUuid   = (UUID) account.get("id");

            if (accessToken == null || accessToken.isBlank()) {
                errors.add(channel + ":" + accountId + " — no access token");
                continue;
            }

            try {
                List<Map<String, Object>> campaigns;
                if ("GOOGLE".equals(channel)) {
                    campaigns = googleAdsApiService.getCampaigns(accessToken, accountId);
                } else if ("TIKTOK".equals(channel)) {
                    campaigns = tikTokApiService.getCampaigns(accessToken, accountId);
                } else {
                    continue;
                }

                for (Map<String, Object> campaign : campaigns) {
                    String extId = (String) campaign.get("id");
                    String name  = (String) campaign.get("name");
                    String status = (String) campaign.get("status");

                    // Upsert campaign record
                    jdbc.update("""
                            INSERT INTO channel_campaigns
                                (tenant_id, channel_account_id, channel, external_id, name, status, synced_at)
                            VALUES (?, ?, ?, ?, ?, ?, now())
                            ON CONFLICT (tenant_id, channel, external_id)
                            DO UPDATE SET name = EXCLUDED.name, status = EXCLUDED.status, synced_at = now()
                            """, tenantId, accountUuid, channel, extId, name, status);

                    // Fetch and store insights
                    Map<String, ?> insights;
                    if ("GOOGLE".equals(channel)) {
                        insights = googleAdsApiService.getCampaignInsights(
                                accessToken, accountId, extId, "LAST_7_DAYS");
                    } else {
                        insights = tikTokApiService.getCampaignInsights(
                                accessToken, accountId, extId, weekAgo, today);
                    }

                    BigDecimal spend = toBigDecimal(insights.get("spend"));
                    long impressions  = toLong(insights.get("impressions"));
                    long clicks       = toLong(insights.get("clicks"));
                    long conversions  = toLong(insights.get("conversions"));

                    jdbc.update("""
                            INSERT INTO channel_insights_history
                                (tenant_id, channel, account_id, campaign_id, ad_set_id, date,
                                 spend, impressions, clicks, conversions)
                            VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, channel, campaign_id, ad_set_id, date)
                            DO UPDATE SET spend = EXCLUDED.spend, impressions = EXCLUDED.impressions,
                                          clicks = EXCLUDED.clicks, conversions = EXCLUDED.conversions
                            """, tenantId, channel, accountId, extId, today,
                            spend, impressions, clicks, conversions);

                    synced++;
                }
            } catch (Exception e) {
                log.warn("Sync failed for {}:{} — {}", channel, accountId, e.getMessage());
                errors.add(channel + ":" + accountId + " — " + e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("synced_campaigns", synced);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        return new BigDecimal(v.toString());
    }

    private long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}
