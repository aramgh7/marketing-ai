package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Audit M-4 / M-20: read-only Graph API lookups used by the publish flow. */
class MetaVideoStatusServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void await_thumbnail_polls_until_meta_renders_one_and_prefers_the_preferred() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        AtomicInteger calls = new AtomicInteger();
        when(rest.exchange(contains("/v123?fields=status"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenAnswer(inv -> ResponseEntity.ok(M.readTree(calls.incrementAndGet() < 3
                        ? "{\"status\":{\"video_status\":\"processing\"}}" : "{\"status\":{\"video_status\":\"ready\"}}")));
        when(rest.exchange(contains("/v123/thumbnails"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenAnswer(inv -> ResponseEntity.ok(M.readTree(calls.get() < 3 ? "{\"data\":[]}"
                        : "{\"data\":[{\"uri\":\"https://cdn/a.jpg\",\"is_preferred\":false},{\"uri\":\"https://cdn/b.jpg\",\"is_preferred\":true}]}")));

        MetaVideoStatusService s = new MetaVideoStatusService(rest, 5, 0);
        assertThat(s.awaitThumbnail("tok", "v123")).isEqualTo("https://cdn/b.jpg");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void await_thumbnail_returns_null_when_still_processing_after_all_attempts() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(contains("/v9?fields=status"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(M.readTree("{\"status\":{\"video_status\":\"processing\"}}")));
        when(rest.exchange(contains("/v9/thumbnails"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(M.readTree("{\"data\":[]}")));
        MetaVideoStatusService s = new MetaVideoStatusService(rest, 2, 0);
        assertThat(s.awaitThumbnail("tok", "v9")).isNull();
        verify(rest, org.mockito.Mockito.times(2)).exchange(contains("/v9/thumbnails"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    void await_thumbnail_fails_fast_on_a_processing_error() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(contains("/v1?fields=status"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(M.readTree("{\"status\":{\"video_status\":\"error\"}}")));
        MetaVideoStatusService s = new MetaVideoStatusService(rest, 3, 0);
        assertThatThrownBy(() -> s.awaitThumbnail("tok", "v1")).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("could not process");
    }

    @Test
    void ad_set_delivery_reads_both_statuses_and_degrades_to_unknown() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(contains("/as1?fields=effective_status,campaign"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(M.readTree("{\"effective_status\":\"PAUSED\",\"campaign\":{\"effective_status\":\"ACTIVE\"}}")));
        when(rest.exchange(contains("/as2?"), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("boom"));
        MetaVideoStatusService s = new MetaVideoStatusService(rest, 1, 0);
        MetaVideoStatusService.DeliveryStatus d = s.adSetDelivery("tok", "as1");
        assertThat(d.adSetEffectiveStatus()).isEqualTo("PAUSED");
        assertThat(d.campaignEffectiveStatus()).isEqualTo("ACTIVE");
        assertThat(s.adSetDelivery("tok", "as2")).isEqualTo(MetaVideoStatusService.DeliveryStatus.UNKNOWN);
        assertThat(s.adSetDelivery("tok", null)).isEqualTo(MetaVideoStatusService.DeliveryStatus.UNKNOWN);
    }

    @Test
    void requests_carry_the_token_as_bearer_header_not_query_param() throws Exception {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(M.readTree("{\"status\":{\"video_status\":\"ready\"}}")));
        MetaVideoStatusService s = new MetaVideoStatusService(rest, 1, 0);
        assertThat(s.videoStatus("secret-token", "v5")).isEqualTo("ready");
        org.mockito.ArgumentCaptor<String> url = org.mockito.ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<HttpEntity<Void>> entity = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(), eq(JsonNode.class));
        assertThat(url.getValue()).doesNotContain("secret-token").startsWith(MetaVideoStatusService.BASE_URL + "/v5");
        assertThat(entity.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer secret-token");
    }
}
