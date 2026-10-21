package com.marketingai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Roadmap Phase 10: pure helpers for client-domain verification and runner keys (no I/O, unit-tested).
 */
public final class DomainVerifyMath {

    public static final String TXT_PREFIX = "_mai-verify.";
    public static final String RUNNER_KEY_PREFIX = "mrk_";

    private static final Pattern FQDN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private DomainVerifyMath() {}

    /** Lower-case FQDN without scheme, port or path; rejects localhost, IP literals and malformed names. */
    public static String normalizeHost(String raw) {
        if (raw == null) throw new IllegalArgumentException("host required");
        String h = raw.trim().toLowerCase();
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int at = h.indexOf('@');
        if (at >= 0) h = h.substring(at + 1);
        int colon = h.indexOf(':');
        if (colon >= 0) h = h.substring(0, colon);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) throw new IllegalArgumentException("host required");
        if ("localhost".equals(h) || h.endsWith(".localhost")) throw new IllegalArgumentException("localhost is not a client domain");
        if (IPV4.matcher(h).matches() || h.contains("[")) throw new IllegalArgumentException("IP addresses are not accepted; use a domain name");
        if (!FQDN.matcher(h).matches()) throw new IllegalArgumentException("not a valid domain name: " + h);
        return h;
    }

    public static String txtRecordName(String host) {
        return TXT_PREFIX + host;
    }

    /** True when any TXT value equals the token (quotes and whitespace stripped). */
    public static boolean txtMatches(List<String> txtValues, String token) {
        if (txtValues == null || token == null) return false;
        for (String v : txtValues) {
            if (v == null) continue;
            String s = v.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            if (s.trim().equals(token)) return true;
        }
        return false;
    }

    /** True when any CNAME value equals the target (trailing dot and case ignored). */
    public static boolean cnameMatches(List<String> cnames, String target) {
        if (cnames == null || target == null || target.isBlank()) return false;
        String t = stripDot(target.trim().toLowerCase());
        for (String c : cnames) {
            if (c != null && stripDot(c.trim().toLowerCase()).equals(t)) return true;
        }
        return false;
    }

    private static String stripDot(String s) {
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** 32 hex chars from SecureRandom. */
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** "mrk_" + 40 hex chars. */
    public static String newRunnerKey() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return RUNNER_KEY_PREFIX + HexFormat.of().formatHex(b);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
