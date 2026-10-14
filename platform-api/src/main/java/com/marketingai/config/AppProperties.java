package com.marketingai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private String mlServiceUrl;
    private String internalApiKey;

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expiryMs = 86400000L;
    }
}
