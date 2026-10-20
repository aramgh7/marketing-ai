package com.marketingai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal LeadConnector (GoHighLevel v2) client — roadmap 8-9.
 *
 * Writes our identifiers into contact custom fields so later transfers between sub-accounts /
 * agencies keep the attribution chain (P1: before any transfer; linking order on the way back is
 * custom fields → email/phone hash → UTM, see {@link GhlContactLinker}).
 *
 * Custom fields are addressed by key; the client creates {@code mai_session_id} and
 * {@code mai_event_id} once in every location (GHL → Settings → Custom Fields).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GhlApiClient {

    public static final String BASE_URL = "https://services.leadconnectorhq.com";
    public static final String API_VERSION = "2021-07-28";
    public static final String FIELD_SESSION = "mai_session_id";
    public static final String FIELD_EVENT = "mai_event_id";

    private final RestTemplate restTemplate;

    /**
     * PUT /contacts/{id} with customFields. Returns true when GHL accepted the update.
     * Never throws: attribution must not fail the webhook that triggered it.
     */
    public boolean writeContactIds(String apiKey, String contactId, String sessionId, String eventId) {
        if (apiKey == null || apiKey.isBlank() || contactId == null || contactId.isBlank()) return false;
        List<Map<String, Object>> fields = new ArrayList<>();
        if (sessionId != null) fields.add(Map.of("key", FIELD_SESSION, "field_value", sessionId));
        if (eventId != null) fields.add(Map.of("key", FIELD_EVENT, "field_value", eventId));
        if (fields.isEmpty()) return false;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        headers.set("Version", API_VERSION);
        try {
            restTemplate.exchange(BASE_URL + "/contacts/" + contactId, HttpMethod.PUT,
                    new HttpEntity<>(Map.of("customFields", fields), headers), String.class);
            log.debug("GHL contact {} custom fields written ({} fields)", contactId, fields.size());
            return true;
        } catch (Exception e) {
            log.warn("GHL custom-field write failed for contact {}: {}", contactId, e.getMessage());
            return false;
        }
    }
}
