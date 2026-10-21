package com.marketingai.config;

import com.marketingai.service.TenantDomainService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Roadmap Phase 10: CORS allow-list = configured origins + {@code https://<host>} / {@code http://<host>} for every
 * VERIFIED tenant domain (architecture §11 "whitelist tenant domains"). The domain list is cached for 60 s so
 * a new domain starts working within a minute of verification without a restart.
 */
@Slf4j
public class TenantDomainCorsSource implements CorsConfigurationSource {

    static final long CACHE_MS = 60_000L;

    private final List<String> configured;
    private final TenantDomainService domainService;

    private volatile CorsConfiguration cached;
    private volatile long cachedAt;

    public TenantDomainCorsSource(List<String> configured, TenantDomainService domainService) {
        this.configured = configured == null ? List.of() : List.copyOf(configured);
        this.domainService = domainService;
    }

    @Override
    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        CorsConfiguration c = cached;
        if (c == null || now - cachedAt > CACHE_MS) {
            c = build(loadHosts());
            cached = c;
            cachedAt = now;
        }
        return c;
    }

    private List<String> loadHosts() {
        try {
            return domainService.verifiedHosts();
        } catch (Exception e) {
            log.debug("tenant domain CORS reload skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /** Pure: same settings as the original static config, plus one origin pattern pair per host. */
    static CorsConfiguration build(List<String> configured, List<String> hosts) {
        List<String> origins = new ArrayList<>(configured);
        for (String h : hosts) {
            if (h == null || h.isBlank()) continue;
            origins.add("https://" + h);
            origins.add("http://" + h);
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    private CorsConfiguration build(List<String> hosts) {
        return build(configured, hosts);
    }
}
