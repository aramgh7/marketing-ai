package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap Phase 10: variant sets — slug / name / angle assignment and block assembly from generated slots. */
class LandingSetMathTest {

    @Test
    void slugs_names_and_angles_are_distinct_per_member() {
        assertThat(LandingSetService.slugFor("spring-raise", 1)).isEqualTo("spring-raise-1");
        assertThat(LandingSetService.nameFor("Spring raise", 3)).isEqualTo("Spring raise — v3");
        assertThat(LandingSetService.setName("Spring raise — v3")).isEqualTo("Spring raise");
        assertThat(LandingSetService.setName("plain")).isEqualTo("plain");
        assertThat(LandingSetService.angleFor(1)).isEqualTo("direct and factual");
        assertThat(LandingSetService.angleFor(5)).isEqualTo(LandingSetService.STYLE_ANGLES.get(4));
        assertThat(LandingSetService.angleFor(6)).isEqualTo(LandingSetService.angleFor(1));   // wraps
        assertThat(LandingSetService.STYLE_ANGLES).hasSize(LandingSetService.MAX_COUNT).doesNotHaveDuplicates();
    }

    @Test
    void hero_block_from_slots() {
        Map<String, Object> b = LandingSetService.assembleBlock("HERO",
                Map.of("headline", "Water is the asset", "subheadline", "506(c) offering", "cta_text", "Book a call"));
        assertThat(b.get("type")).isEqualTo("HERO");
        assertThat(b.get("id")).isEqualTo("hero");
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) b.get("data");
        assertThat(d).containsEntry("headline", "Water is the asset").containsEntry("cta_text", "Book a call");
    }

    @Test
    void problem_and_faq_blocks_build_arrays_and_skip_empty_slots() {
        @SuppressWarnings("unchecked")
        Map<String, Object> p = LandingSetService.assembleBlock("PROBLEM",
                Map.of("heading", "Why now", "pain_point_1", "Drought", "pain_point_2", "", "pain_point_3", "Prices"));
        @SuppressWarnings("unchecked") Map<String, Object> pd = (Map<String, Object>) p.get("data");
        assertThat((List<Object>) pd.get("pain_points")).containsExactly("Drought", "Prices");

        Map<String, Object> f = LandingSetService.assembleBlock("FAQ",
                Map.of("q1", "Minimum?", "a1", "$75k", "q2", "", "a2", "x", "q3", "Liquidity?", "a3", "Annual window"));
        @SuppressWarnings("unchecked") Map<String, Object> fd = (Map<String, Object>) f.get("data");
        @SuppressWarnings("unchecked") List<Map<String, String>> items = (List<Map<String, String>>) fd.get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("question", "Minimum?").containsEntry("answer", "$75k");
        assertThat(items.get(1)).containsEntry("question", "Liquidity?");
        assertThat(fd.get("heading")).isEqualTo("FAQ");
    }

    @Test
    void unknown_block_type_passes_slots_through() {
        Map<String, Object> b = LandingSetService.assembleBlock("PROOF", Map.of("heading", "Investors say"));
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) b.get("data");
        assertThat(d).containsEntry("heading", "Investors say");
        assertThat(b.get("id")).isEqualTo("proof");
    }

    @Test
    void headline_of_blocks_json_prefers_hero_then_first_text() {
        String blocks = "[{\"type\":\"PROBLEM\",\"id\":\"problem\",\"data\":{\"heading\":\"Why\"}},"
                + "{\"type\":\"HERO\",\"id\":\"hero\",\"data\":{\"headline\":\"Big idea\"}}]";
        assertThat(LandingSetService.headlineOf(blocks)).isEqualTo("Big idea");
        assertThat(LandingSetService.headlineOf("[{\"type\":\"CTA\",\"data\":{\"heading\":\"Act\"}}]")).isEqualTo("Act");
        assertThat(LandingSetService.headlineOf("[]")).isNull();
        assertThat(LandingSetService.headlineOf("not json")).isNull();
    }

    @Test
    void block_types_default_and_dedupe() {
        assertThat(LandingSetService.blockTypes(null)).isEqualTo(LandingSetService.DEFAULT_BLOCK_TYPES);
        assertThat(LandingSetService.blockTypes(List.of())).isEqualTo(LandingSetService.DEFAULT_BLOCK_TYPES);
        assertThat(LandingSetService.blockTypes(List.of("hero", "HERO", "cta"))).containsExactly("HERO", "CTA");
        assertThat(LandingSetService.LANDING_REJECT_REASONS).contains("OFF_BRIEF", "COMPLIANCE", "TONE");
    }
}
