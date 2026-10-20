package com.marketingai.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Pure helpers for Meta long-lived token rotation (roadmap 9-2; arch §15.15; vision §12.7).
 * Long-lived user tokens live 60 days; exchanging one before day 60 yields a fresh 60-day token.
 *
 * Age policy (days since {@code meta_token_created_at}):
 *   < 50      OK — nothing to do
 *   50 … 57   ROTATE — exchange; on failure mark EXPIRING + alert
 *   ≥ 58      INVALID — Meta writes are paused, autonomy drops to OBSERVER until re-auth
 */
public final class MetaTokenExchange {

    public static final String GRAPH_API = "https://graph.facebook.com/v20.0";
    public static final int ROTATE_FROM_DAY = 50;
    public static final int INVALID_FROM_DAY = 58;

    public enum Decision { OK, ROTATE, INVALID }

    private MetaTokenExchange() {}

    public static Decision decide(long daysOld) {
        if (daysOld >= INVALID_FROM_DAY) return Decision.INVALID;
        if (daysOld >= ROTATE_FROM_DAY) return Decision.ROTATE;
        return Decision.OK;
    }

    public static boolean credentialsPresent(String appId, String appSecret) {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /** GET URL for the fb_exchange_token grant. The token travels as a query parameter by Meta's design. */
    public static String exchangeUrl(String appId, String appSecret, String currentToken) {
        return GRAPH_API + "/oauth/access_token"
                + "?grant_type=fb_exchange_token"
                + "&client_id=" + enc(appId)
                + "&client_secret=" + enc(appSecret)
                + "&fb_exchange_token=" + enc(currentToken);
    }

    /** Parse the exchange response; null when it carries no usable token. */
    public static String parseNewToken(Map<String, Object> response) {
        if (response == null) return null;
        Object token = response.get("access_token");
        if (token == null) return null;
        String t = token.toString().trim();
        return t.isEmpty() ? null : t;
    }

    /** Masked token for logs/audit: first 6 + last 4 characters. */
    public static String mask(String token) {
        if (token == null) return "null";
        if (token.length() <= 12) return "***";
        return token.substring(0, 6) + "…" + token.substring(token.length() - 4);
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }
}
