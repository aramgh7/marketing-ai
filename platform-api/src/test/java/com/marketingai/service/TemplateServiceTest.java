package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateServiceTest {

    @Test
    void platformDefaults_fiveTemplates() {
        List<Map<String, Object>> defaults = TemplateService.platformDefaults();
        assertThat(defaults).hasSize(5);
    }

    @Test
    void platformDefaults_allHaveRequiredFields() {
        for (Map<String, Object> d : TemplateService.platformDefaults()) {
            assertThat(d).containsKey("name");
            assertThat(d).containsKey("blocks");
            assertThat(d).containsKey("tags");
            assertThat((List<?>) d.get("blocks")).isNotEmpty();
        }
    }

    @Test
    void platformDefaults_authorityHasHeroFirst() {
        Map<String, Object> authority = TemplateService.platformDefaults().get(0);
        assertThat(authority.get("name")).isEqualTo("Authority");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) authority.get("blocks");
        assertThat(blocks.get(0).get("block_type")).isEqualTo("HERO");
    }

    @Test
    void platformDefaults_fastTrackIsMinimal() {
        Map<String, Object> fastTrack = TemplateService.platformDefaults().stream()
                .filter(d -> "Fast Track".equals(d.get("name")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<?> blocks = (List<?>) fastTrack.get("blocks");
        assertThat(blocks).hasSize(3);
    }

    @Test
    void platformDefaults_educationFirstHasMostBlocks() {
        Map<String, Object> edu = TemplateService.platformDefaults().stream()
                .filter(d -> "Education First".equals(d.get("name")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<?> blocks = (List<?>) edu.get("blocks");
        assertThat(blocks.size()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void platformDefaults_allBlocksHaveLayoutVariant() {
        for (Map<String, Object> def : TemplateService.platformDefaults()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) def.get("blocks");
            for (Map<String, Object> block : blocks) {
                assertThat(block)
                        .as("block in template '%s' missing layout_variant", def.get("name"))
                        .containsKey("layout_variant");
                assertThat(TemplateService.VALID_LAYOUT_VARIANTS)
                        .as("layout_variant invalid in template '%s'", def.get("name"))
                        .contains((String) block.get("layout_variant"));
            }
        }
    }

    @Test
    void assetService_l1L2L3ValuesNotEmpty() {
        assertThat(AssetService.L1_VALUES).isNotEmpty();
        assertThat(AssetService.L2_VALUES).isNotEmpty();
        assertThat(AssetService.L3_VALUES).isNotEmpty();
    }

    @Test
    void assetService_heroBackgroundInL2() {
        assertThat(AssetService.L2_VALUES).contains("hero_background");
    }

    @Test
    void assetService_founderPortraitInL1() {
        assertThat(AssetService.L1_VALUES).contains("founder_portrait");
    }

    @Test
    void templateService_validLayoutVariantsContainsExpected() {
        assertThat(TemplateService.VALID_LAYOUT_VARIANTS).containsExactlyInAnyOrder(
                "centered", "left-text-right-image", "full-bleed-video", "split-screen");
    }

    @Test
    void templateService_validBgTypesContainsExpected() {
        assertThat(TemplateService.VALID_BG_TYPES).containsExactlyInAnyOrder(
                "color", "gradient", "image", "video");
    }
}
