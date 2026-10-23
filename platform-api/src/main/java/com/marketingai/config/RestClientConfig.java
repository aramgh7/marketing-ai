package com.marketingai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
public class RestClientConfig {

    static final String INTERNAL_KEY_HEADER = "X-Internal-API-Key";

    /** Connect timeout (ms) for all outbound HTTP calls (audit M-8/D-9). */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Read/socket timeout (ms) for all outbound HTTP calls (audit M-8/D-9). */
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Every ML-service router now requires {@code X-Internal-API-Key} (audit §3.4, fail closed).
     * Rather than touching 25 call sites, the shared RestTemplate adds the header to any request
     * whose URL starts with the configured ML base URL (and only those).
     * Timeouts set per audit M-8/D-9: 10 s connect, 30 s read.
     */
    @Bean
    public RestTemplate restTemplate(AppProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate template = new RestTemplate(factory);
        template.setInterceptors(List.of(internalKeyInterceptor(props)));
        return template;
    }

    static ClientHttpRequestInterceptor internalKeyInterceptor(AppProperties props) {
        return (request, body, execution) -> {
            String base = props.getMlServiceUrl();
            if (base != null && !base.isBlank()
                    && request.getURI().toString().startsWith(base)
                    && !request.getHeaders().containsKey(INTERNAL_KEY_HEADER)) {
                request.getHeaders().set(INTERNAL_KEY_HEADER, props.getInternalApiKey());
            }
            return execution.execute(request, body);
        };
    }
}
