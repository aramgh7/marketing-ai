package com.marketingai.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Roadmap 8-8: atoms validated against the tenant schema (mirrors the ML-side validator). */
class OfferSchemaServiceTest {

    private static final List<Map<String, Object>> INVESTOR_RAISE = List.of(
            Map.of("key", "first_touch_format", "type", "enum", "required", true,
                   "values", List.of("call", "webinar", "event", "video_presentation")),
            Map.of("key", "entry_threshold_shown", "type", "enum", "required", true,
                   "values", List.of("none", "from_25k", "from_50k")),
            Map.of("key", "proof_type", "type", "enum", "required", true,
                   "values", List.of("adoption_speed", "field_footage", "founder", "round_structure")),
            Map.of("key", "urgency", "type", "enum", "required", true, "must_be_real", true,
                   "values", List.of("none", "round_close_date", "allocation_left")),
            Map.of("key", "founder_presence", "type", "bool", "required", false),
            Map.of("key", "followup_offer", "type", "enum", "required", true,
                   "values", List.of("agenda", "round_materials_before_call", "none")));

    private static Map<String, Object> base() {
        Map<String, Object> m = new HashMap<>();
        m.put("first_touch_format", "call");
        m.put("entry_threshold_shown", "from_25k");
        m.put("proof_type", "founder");
        m.put("urgency", "none");
        m.put("founder_presence", true);
        m.put("followup_offer", "agenda");
        return m;
    }

    @Test
    void validOfferHasNoErrors() {
        assertThat(OfferSchemaService.validateAtoms(base(), INVESTOR_RAISE, null)).isEmpty();
    }

    @Test
    void missingRequiredAndBadEnum() {
        Map<String, Object> atoms = base();
        atoms.remove("proof_type");
        atoms.put("first_touch_format", "dinner");
        List<String> errors = OfferSchemaService.validateAtoms(atoms, INVESTOR_RAISE, null);
        assertThat(errors).anyMatch(e -> e.contains("proof_type is required"));
        assertThat(errors).anyMatch(e -> e.contains("first_touch_format='dinner'"));
    }

    @Test
    void urgencyMustBeReal() {
        Map<String, Object> atoms = base();
        atoms.put("urgency", "allocation_left");
        assertThat(OfferSchemaService.validateAtoms(atoms, INVESTOR_RAISE, null))
                .anyMatch(e -> e.contains("must be backed by a real source"));
        assertThat(OfferSchemaService.validateAtoms(atoms, INVESTOR_RAISE, "allocation_api")).isEmpty();
        atoms.put("urgency_source", "crm_deadline");
        assertThat(OfferSchemaService.validateAtoms(atoms, INVESTOR_RAISE, null)).isEmpty();
    }

    @Test
    void unknownAtomsAndBooleans() {
        Map<String, Object> atoms = base();
        atoms.put("discount", "10%");
        atoms.put("founder_presence", "maybe");
        List<String> errors = OfferSchemaService.validateAtoms(atoms, INVESTOR_RAISE, null);
        assertThat(errors).anyMatch(e -> e.contains("unknown atom 'discount'"));
        assertThat(errors).anyMatch(e -> e.contains("founder_presence must be a boolean"));
    }

    @Test
    void schemaModeDecidesMarginVersusCompliance() {
        OfferSchemaService.Schema investor = new OfferSchemaService.Schema("INVESTOR_RAISE", "investor_raise", "COMPLIANCE_PASS", INVESTOR_RAISE);
        OfferSchemaService.Schema b2b = new OfferSchemaService.Schema("B2B", "b2b", "MARGIN_FLOOR", List.of());
        assertThat(investor.complianceReplacesMargin()).isTrue();
        assertThat(b2b.complianceReplacesMargin()).isFalse();
    }
}
