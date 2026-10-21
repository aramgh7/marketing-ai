package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Roadmap Phase 11-3 / 11-4: destination URL, url_tags, ad name and linkage health. */
class CreativePublishMathTest {

    @Test
    void destination_url_is_https_host_slash_l_slug() {
        assertThat(CreativePublishMath.destinationUrl("invest.client.com", "main")).isEqualTo("https://invest.client.com/l/main");
        assertThat(CreativePublishMath.destinationUrl("https://Invest.Client.com/", "main-2")).isEqualTo("https://invest.client.com/l/main-2");
        assertThatThrownBy(() -> CreativePublishMath.destinationUrl("invest.client.com", "Main Page")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreativePublishMath.destinationUrl("", "main")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CreativePublishMath.destinationUrl("h", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void url_tags_carry_the_creative_marker_and_meta_macros() {
        assertThat(CreativePublishMath.URL_TAGS).startsWith("mai_c={{ad.id}}");
        assertThat(CreativePublishMath.URL_TAGS).contains("mai_p={{placement}}").contains("mai_s={{site_source_name}}");
        assertThat(CreativePublishMath.hasTagMarker(CreativePublishMath.URL_TAGS)).isTrue();
        assertThat(CreativePublishMath.hasTagMarker("utm_source=meta")).isFalse();
        assertThat(CreativePublishMath.hasTagMarker(null)).isFalse();
    }

    @Test
    void ad_name_starts_with_the_creative_code_and_is_capped() {
        assertThat(CreativePublishMath.adName("MAI-7A3F2C", "Founder story")).isEqualTo("MAI-7A3F2C · Founder story");
        assertThat(CreativePublishMath.adName(null, null)).isEqualTo("MAI · creative");
        assertThat(CreativePublishMath.adName("MAI-7A3F2C", "x".repeat(400))).hasSize(255);
        assertThat(AdReconcileMath.creativeCodeIn(CreativePublishMath.adName("MAI-7A3F2C", "Founder story"))).isEqualTo("MAI-7A3F2C");
    }

    @Test
    void linkage_health_is_red_on_unmapped_untagged_or_drifted_active_ads() {
        assertThat(LinkageHealthService.evaluate(List.of()).healthy()).isTrue();
        List<Map<String, Object>> rows = List.of(
                row("ACTIVE", "c1", CreativePublishMath.URL_TAGS),
                row("ACTIVE", "c2", CreativePublishMath.URL_TAGS));
        assertThat(LinkageHealthService.evaluate(rows).healthy()).isTrue();

        LinkageHealthService.Health h = LinkageHealthService.evaluate(List.of(
                row("ACTIVE", "c1", CreativePublishMath.URL_TAGS),
                row("ACTIVE", null, CreativePublishMath.URL_TAGS),     // unmapped
                row("ACTIVE", "c3", "utm_source=meta"),                // no marker
                row("UNMANAGED", null, null),
                row("DRIFT", "c4", CreativePublishMath.URL_TAGS)));
        assertThat(h.healthy()).isFalse();
        assertThat(h.activeAds()).isEqualTo(5);
        assertThat(h.unmanaged()).isEqualTo(2);
        assertThat(h.missingTags()).isEqualTo(1);
        assertThat(h.drift()).isEqualTo(1);
        assertThat(h.summary()).contains("5 active");
    }

    private static Map<String, Object> row(String status, String creativeId, String tags) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("creative_id", creativeId);
        m.put("url_tags", tags);
        return m;
    }
}
