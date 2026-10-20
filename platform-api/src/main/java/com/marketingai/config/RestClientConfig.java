package com.marketingai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Configuration
public class RestClientConfig {

    static final String INTERNAL_KEY_HEADER = "X-Internal-API-Key";

    /**
     * Every ML-service router now requires {@code X-Internal-API-Key} (audit §3.4, fail closed).
     * Rather than touching 25 call sites, the shared RestTemplate adds the header to any request
     * whose URL starts with the configured ML base URL (and only those).
     */
    @Bean
    public RestTemplate restTemplate(AppProperties props) {
        RestTemplate template = new RestTemplate();
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
