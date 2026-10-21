package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap Phase 11-4: matching hand-made ads by creative code, status mapping, drift diff. */
class AdReconcileMathTest {

    @Test
    void creative_code_is_found_in_ad_names_case_insensitively() {
        assertThat(AdReconcileMath.creativeCodeIn("MAI-7A3F2C · Founder story 30s")).isEqualTo("MAI-7A3F2C");
        assertThat(AdReconcileMath.creativeCodeIn("copy of mai-7a3f2c")).isEqualTo("MAI-7A3F2C");
        assertThat(AdReconcileMath.creativeCodeIn("Founder story")).isNull();
        assertThat(AdReconcileMath.creativeCodeIn("MAI-7A3F2 short")).isNull();
        assertThat(AdReconcileMath.creativeCodeIn(null)).isNull();
    }

    @Test
    void effective_status_maps_to_map_status_and_publish_status() {
        assertThat(AdReconcileMath.statusFor("ACTIVE")).isEqualTo("ACTIVE");
        assertThat(AdReconcileMath.statusFor("ADSET_PAUSED")).isEqualTo("PAUSED");
        assertThat(AdReconcileMath.statusFor("DELETED")).isEqualTo("ARCHIVED");
        assertThat(AdReconcileMath.statusFor("DISAPPROVED")).isEqualTo("REJECTED");
        assertThat(AdReconcileMath.statusFor("PENDING_REVIEW")).isEqualTo("PENDING_REVIEW");
        assertThat(AdReconcileMath.statusFor(null)).isEqualTo("UNKNOWN");
        assertThat(AdReconcileMath.publishStatusFor("ACTIVE")).isEqualTo("ACTIVE_IN_META");
        assertThat(AdReconcileMath.publishStatusFor("APPROVED")).isEqualTo("PAUSED_IN_META");
        assertThat(AdReconcileMath.publishStatusFor("ARCHIVED")).isEqualTo("NOT_IN_META");
        assertThat(AdReconcileMath.publishStatusFor("UNMANAGED")).isEqualTo("UNMANAGED");
        assertThat(AdReconcileMath.moderationOwned("PENDING_REVIEW")).isTrue();
        assertThat(AdReconcileMath.moderationOwned("ACTIVE")).isFalse();
    }

    @Test
    void drift_reports_only_fields_the_platform_set_and_that_changed() {
        String tags = CreativePublishMath.URL_TAGS;
        Map<String, Map<String, String>> none = AdReconcileMath.drift(
                "https://invest.client.com/l/main", "https://invest.client.com/l/main/",
                tags, tags, "v1", "v1", null, "whatever");
        assertThat(none).isEmpty();

        Map<String, Map<String, String>> d = AdReconcileMath.drift(
                "https://invest.client.com/l/main", "https://other.site/x",
                tags, "", "v1", "v2", null, null);
        assertThat(d).containsOnlyKeys("link_url", "url_tags", "video_id");
        assertThat(d.get("link_url").get("expected")).isEqualTo("https://invest.client.com/l/main");
        assertThat(d.get("link_url").get("actual")).isEqualTo("https://other.site/x");
        assertThat(d.get("url_tags").get("actual")).isEmpty();
    }
}
