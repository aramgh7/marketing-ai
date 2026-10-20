package com.marketingai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─── Roadmap 9-8: polling reads for GhlSyncScheduler ─────────────────────

    /** GET /calendars/events for a location and window. Never throws; empty list on failure. */
    public List<GhlSyncMath.RemoteAppointment> listAppointments(String apiKey, String locationId, Instant from, Instant to) {
        List<GhlSyncMath.RemoteAppointment> out = new ArrayList<>();
        JsonNode root = get(apiKey, BASE_URL + "/calendars/events?locationId=" + locationId
                + "&startTime=" + from.toEpochMilli() + "&endTime=" + to.toEpochMilli());
        if (root == null) return out;
        JsonNode events = root.has("events") ? root.get("events") : root;
        if (!events.isArray()) return out;
        for (JsonNode e : events) {
            Instant start = null;
            try { start = Instant.parse(e.path("startTime").asText("")); } catch (Exception ignored) { }
            out.add(new GhlSyncMath.RemoteAppointment(
                    e.path("id").asText(null), e.path("contactId").asText(null), start,
                    e.path("appointmentStatus").asText(e.path("status").asText(null))));
        }
        return out;
    }

    /** GET /opportunities/search?status=won for a location. Never throws; empty list on failure. */
    public List<GhlSyncMath.WonDeal> listWonOpportunities(String apiKey, String locationId) {
        List<GhlSyncMath.WonDeal> out = new ArrayList<>();
        JsonNode root = get(apiKey, BASE_URL + "/opportunities/search?location_id=" + locationId + "&status=won&limit=100");
        if (root == null) return out;
        JsonNode opps = root.path("opportunities");
        if (!opps.isArray()) return out;
        for (JsonNode o : opps) {
            if (!"won".equalsIgnoreCase(o.path("status").asText(""))) continue;
            out.add(new GhlSyncMath.WonDeal(o.path("id").asText(null), o.path("contactId").asText(null),
                    o.path("monetaryValue").asDouble(0.0)));
        }
        return out;
    }

    private JsonNode get(String apiKey, String url) {
        if (apiKey == null || apiKey.isBlank()) return null;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.set("Version", API_VERSION);
        try {
            String body = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            return body == null ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("GHL GET failed ({}): {}", url.substring(0, Math.min(url.length(), 80)), e.getMessage());
            return null;
        }
    }

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
