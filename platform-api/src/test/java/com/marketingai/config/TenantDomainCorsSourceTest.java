package com.marketingai.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap Phase 10: CORS allow-list = configured origins + verified tenant domains. */
class TenantDomainCorsSourceTest {

    @Test
    void verified_hosts_are_added_as_https_and_http_origins() {
        CorsConfiguration c = TenantDomainCorsSource.build(List.of("http://localhost:3000"), java.util.Arrays.asList("go.client.com", "", null));
        assertThat(c.getAllowedOriginPatterns())
                .containsExactly("http://localhost:3000", "https://go.client.com", "http://go.client.com");
        assertThat(c.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        assertThat(c.getAllowedHeaders()).contains("Authorization", "Content-Type", "X-Requested-With");
        assertThat(c.getAllowCredentials()).isTrue();
        assertThat(c.getMaxAge()).isEqualTo(3600L);
    }

    @Test
    void no_domains_keeps_the_configured_list_only() {
        CorsConfiguration c = TenantDomainCorsSource.build(List.of("https://app.example.com"), List.of());
        assertThat(c.getAllowedOriginPatterns()).containsExactly("https://app.example.com");
    }
}
