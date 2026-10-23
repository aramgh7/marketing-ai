package com.marketingai.service;

import com.marketingai.entity.MetaAdMap;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    // ─── Audit M-4: thumbnail choice ───

    @Test
    void preferred_thumbnail_wins_else_first_else_null() {
        assertThat(CreativePublishMath.preferredThumbnail(List.of(
                thumb("https://cdn/a.jpg", false), thumb("https://cdn/b.jpg", true)))).isEqualTo("https://cdn/b.jpg");
        assertThat(CreativePublishMath.preferredThumbnail(List.of(
                thumb("https://cdn/a.jpg", false), thumb("https://cdn/c.jpg", false)))).isEqualTo("https://cdn/a.jpg");
        assertThat(CreativePublishMath.preferredThumbnail(List.of(thumb(null, true)))).isNull();
        assertThat(CreativePublishMath.preferredThumbnail(List.of())).isNull();
        assertThat(CreativePublishMath.preferredThumbnail(null)).isNull();
        assertThat(CreativePublishMath.usableAsImageUrl("https://client.com/t.jpg")).isTrue();
        assertThat(CreativePublishMath.usableAsImageUrl("/files/t/c/thumb.jpg")).isFalse();
        assertThat(CreativePublishMath.usableAsImageUrl(null)).isFalse();
    }

    // ─── Audit M-5: publish intent ───

    @Test
    void publish_markers_are_unique_and_recognised() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-22T10:00:00Z");
        assertThat(CreativePublishMath.pendingMarker(id)).isEqualTo("pending:" + id);
        assertThat(CreativePublishMath.failedMarker(id, at)).isEqualTo("failed:" + id + ":" + at.toEpochMilli());
        assertThat(CreativePublishMath.isMarker(CreativePublishMath.pendingMarker(id))).isTrue();
        assertThat(CreativePublishMath.isMarker(CreativePublishMath.failedMarker(id, at))).isTrue();
        assertThat(CreativePublishMath.isMarker(null)).isTrue();
        assertThat(CreativePublishMath.isMarker("120210000000001")).isFalse();
        assertThat(CreativePublishMath.pendingMarker(id).length()).isLessThanOrEqualTo(100);   // VARCHAR(100)
        assertThat(CreativePublishMath.failedMarker(id, at).length()).isLessThanOrEqualTo(100);
    }

    @Test
    void existing_row_decides_free_conflict_in_progress_resume_or_stale() {
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        UUID creative = UUID.randomUUID();
        // nothing for this ad set → FREE; another ad set's live row does not matter
        assertThat(CreativePublishMath.existing(List.of(map("as-2", "ACTIVE", "1", null, now)), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.FREE);
        assertThat(CreativePublishMath.existing(List.of(), "as-1", now).state()).isEqualTo(CreativePublishMath.PublishState.FREE);
        // archived / rejected rows and failed attempts without a real ad never block
        assertThat(CreativePublishMath.existing(List.of(
                map("as-1", "ARCHIVED", "1", null, now), map("as-1", "REJECTED", "2", null, now),
                map("as-1", "PUBLISH_FAILED", CreativePublishMath.failedMarker(creative, now), null, now)), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.FREE);
        // a live ad (any moderation / delivery state) → CONFLICT, with the row
        for (String st : List.of("PENDING_REVIEW", "APPROVED", "ACTIVE", "PAUSED", "DRIFT", "UNKNOWN")) {
            CreativePublishMath.Existing ex = CreativePublishMath.existing(List.of(map("as-1", st, "777", null, now)), "as-1", now);
            assertThat(ex.state()).as(st).isEqualTo(CreativePublishMath.PublishState.CONFLICT);
            assertThat(ex.row().getMetaAdId()).isEqualTo("777");
        }
        // a failed attempt that still owns a real ad id (delete failed) blocks too
        assertThat(CreativePublishMath.existing(List.of(map("as-1", "PUBLISH_FAILED", "778", null, now)), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.CONFLICT);
        // PUBLISHING: running → IN_PROGRESS; checkpoint "video processing" → RESUME; untouched for 30 min → STALE
        String pending = CreativePublishMath.pendingMarker(creative);
        assertThat(CreativePublishMath.existing(List.of(map("as-1", "PUBLISHING", pending, "{\"stage\":\"IN_PROGRESS\"}", now.minusSeconds(60))), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.IN_PROGRESS);
        assertThat(CreativePublishMath.existing(List.of(map("as-1", "PUBLISHING", pending, "{\"stage\":\"VIDEO_PROCESSING\"}", now.minusSeconds(60))), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.RESUME);
        assertThat(CreativePublishMath.existing(List.of(map("as-1", "PUBLISHING", pending, "{\"stage\":\"IN_PROGRESS\"}", now.minus(Duration.ofMinutes(31)))), "as-1", now).state())
                .isEqualTo(CreativePublishMath.PublishState.STALE);
        assertThat(CreativePublishMath.stage("{\"stage\":\"VIDEO_PROCESSING\",\"error\":\"x\"}")).isEqualTo("VIDEO_PROCESSING");
        assertThat(CreativePublishMath.stage(null)).isNull();
    }

    // ─── Audit M-20: delivery warning before RESUME ───

    @Test
    void delivery_warning_when_ad_set_or_campaign_is_not_active() {
        assertThat(CreativePublishMath.deliveryWarning("ACTIVE", "ACTIVE")).isNull();
        assertThat(CreativePublishMath.deliveryWarning(null, null)).isNull();                 // read failed: do not block
        assertThat(CreativePublishMath.deliveryWarning("PAUSED", "ACTIVE")).isEqualTo(CreativePublishMath.ADSET_PAUSED_WARNING);
        assertThat(CreativePublishMath.deliveryWarning("CAMPAIGN_PAUSED", null)).isEqualTo(CreativePublishMath.ADSET_PAUSED_WARNING);
        assertThat(CreativePublishMath.deliveryWarning("PAUSED", "PAUSED")).isEqualTo(CreativePublishMath.CAMPAIGN_PAUSED_WARNING);
        assertThat(CreativePublishMath.deliveryWarning("WITH_ISSUES", "ACTIVE")).contains("ad set is WITH_ISSUES");
        assertThat(CreativePublishMath.publishStatusAfterActivate(null)).isEqualTo("ACTIVE_IN_META");
        assertThat(CreativePublishMath.publishStatusAfterActivate(CreativePublishMath.ADSET_PAUSED_WARNING)).isEqualTo("APPROVED_PAUSED");
    }

    // ─── Roadmap 11-3c: approved render ───

    @Test
    void render_readiness_requires_an_approved_render_only_when_renders_exist() {
        assertThat(CreativePublishMath.renderReady(0, 0)).isTrue();
        assertThat(CreativePublishMath.renderReady(2, 0)).isFalse();
        assertThat(CreativePublishMath.renderReady(2, 1)).isTrue();
        assertThat(CreativePublishMath.renderDetail(0, 0)).contains("uploaded file");
        assertThat(CreativePublishMath.renderDetail(2, 0)).contains("none approved");
        assertThat(CreativePublishMath.renderDetail(2, 1)).isEqualTo("1 approved of 2");
    }

    private static Map<String, Object> thumb(String uri, boolean preferred) {
        Map<String, Object> m = new HashMap<>();
        m.put("uri", uri);
        m.put("is_preferred", preferred);
        return m;
    }

    private static MetaAdMap map(String adSetId, String status, String metaAdId, String drift, Instant touched) {
        MetaAdMap m = new MetaAdMap();
        m.setMetaAdsetId(adSetId);
        m.setStatus(status);
        m.setMetaAdId(metaAdId);
        m.setDrift(drift);
        m.setCreatedAt(touched);
        m.setUpdatedAt(touched);
        return m;
    }

    private static Map<String, Object> row(String status, String creativeId, String tags) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("creative_id", creativeId);
        m.put("url_tags", tags);
        return m;
    }
}
