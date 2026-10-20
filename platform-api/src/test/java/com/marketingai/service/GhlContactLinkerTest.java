package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-9: our ids survive in GHL custom fields and are read back in either payload shape. */
class GhlContactLinkerTest {

    @Test
    void readsCustomDataMap() {
        Map<String, Object> raw = Map.of("customData", Map.of("mai_session_id", "sess-1"));
        assertThat(GhlContactLinker.extractCustomField(raw, "mai_session_id")).contains("sess-1");
    }

    @Test
    void readsCustomFieldsListByKeyOrName() {
        Map<String, Object> raw = Map.of("customFields", List.of(
                Map.of("id", "abc", "key", "mai_session_id", "field_value", "sess-2"),
                Map.of("name", "mai event id", "value", "evt-9")));
        assertThat(GhlContactLinker.extractCustomField(raw, "mai_session_id")).contains("sess-2");
        assertThat(GhlContactLinker.extractCustomField(raw, "mai_event_id")).contains("evt-9");
    }

    @Test
    void readsTopLevelAndIgnoresBlank() {
        assertThat(GhlContactLinker.extractCustomField(Map.of("mai_session_id", " sess-3 "), "mai_session_id")).contains("sess-3");
        assertThat(GhlContactLinker.extractCustomField(Map.of("customData", Map.of("mai_session_id", "  ")), "mai_session_id")).isEmpty();
        assertThat(GhlContactLinker.extractCustomField(null, "mai_session_id")).isEmpty();
    }

    @Test
    void phoneNormalisation() {
        assertThat(GhlContactLinker.normalisePhone("+1 (415) 555-0100")).isEqualTo("14155550100");
        assertThat(GhlContactLinker.normalisePhone("12345")).isNull();
        assertThat(GhlContactLinker.normalisePhone(null)).isNull();
    }
}
