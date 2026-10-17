package com.marketingai.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OfferValidatorTest {

    private OfferValidator validator;

    @BeforeEach
    void setUp() {
        validator = new OfferValidator();
    }

    @Test
    void valid_standard_discount_offer() {
        var result = validator.validate(Map.of(
                "discount_type", "percentage",
                "discount_depth", 15.0,
                "guarantee_type", "money_back",
                "guarantee_days", 30,
                "has_real_urgency", true,
                "urgency_source", "crm_deadline"
        ));
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void discount_type_none_with_nonzero_depth_fails() {
        var result = validator.validate(Map.of(
                "discount_type", "none",
                "discount_depth", 10.0
        ));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("discount_depth=0"));
    }

    @Test
    void discount_type_none_with_zero_depth_passes() {
        var result = validator.validate(Map.of(
                "discount_type", "none",
                "discount_depth", 0.0
        ));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void guarantee_type_none_with_days_set_fails() {
        var result = validator.validate(Map.of(
                "guarantee_type", "none",
                "guarantee_days", 30
        ));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("guarantee_days=null"));
    }

    @Test
    void guarantee_type_none_with_null_days_passes() {
        var result = validator.validate(Map.of("guarantee_type", "none"));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void urgency_true_with_invalid_source_fails() {
        var result = validator.validate(Map.of(
                "has_real_urgency", true,
                "urgency_source", "made_up_source"
        ));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("urgency_source"));
    }

    @Test
    void urgency_true_with_missing_source_fails() {
        var result = validator.validate(Map.of("has_real_urgency", true));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("urgency_source to be set"));
    }

    @Test
    void urgency_true_with_valid_source_passes() {
        for (String src : new String[]{"inventory_api", "crm_deadline", "seasonal_window"}) {
            var result = validator.validate(Map.of("has_real_urgency", true, "urgency_source", src));
            assertThat(result.valid()).as("source: " + src).isTrue();
        }
    }

    @Test
    void empty_offer_passes_all_checks() {
        var result = validator.validate(Map.of());
        assertThat(result.valid()).isTrue();
    }
}
