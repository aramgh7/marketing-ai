package com.marketingai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit fix 1.4: application.yml used the key app.ml-service.url, which relaxed binding maps to a
 * nested "mlService.url" — not to AppProperties.mlServiceUrl. The field stayed null and every
 * Java -> FastAPI call built a "null/..." URI. The key is now app.ml-service-url.
 */
class AppPropertiesBindingTest {

    private static AppProperties bind(Map<String, String> props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind("app", AppProperties.class)
                .orElseGet(AppProperties::new);
    }

    @Test
    void mlServiceUrl_bindsFromKebabCaseKey() {
        AppProperties p = bind(Map.of(
                "app.ml-service-url", "http://ml-service:8000",
                "app.internal-api-key", "k"));
        assertThat(p.getMlServiceUrl()).isEqualTo("http://ml-service:8000");
        assertThat(p.getInternalApiKey()).isEqualTo("k");
    }

    @Test
    void mlServiceUrl_doesNotBindFromNestedKey_regressionGuard() {
        // The old (broken) key shape must not silently bind; documents why application.yml changed.
        AppProperties p = bind(Map.of("app.ml-service.url", "http://ml-service:8000"));
        assertThat(p.getMlServiceUrl()).isNull();
    }
}
