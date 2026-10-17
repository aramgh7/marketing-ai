"""Tests for saturation_service.py — Phase 6-2."""
import pytest
from app.services.saturation_service import (
    compute_reach_efficiency,
    classify_saturation,
    differentiate_diagnosis,
)


class TestComputeReachEfficiency:
    def test_equal_efficiency_returns_one(self):
        eff = compute_reach_efficiency(1000, 1000, 100, 100)
        assert eff == pytest.approx(1.0)

    def test_declining_efficiency(self):
        eff = compute_reach_efficiency(500, 1000, 100, 100)
        assert eff == pytest.approx(0.5)

    def test_improving_efficiency(self):
        eff = compute_reach_efficiency(2000, 1000, 100, 100)
        assert eff == pytest.approx(2.0)

    def test_zero_spend_returns_one(self):
        eff = compute_reach_efficiency(1000, 1000, 0, 0)
        assert eff == pytest.approx(1.0)

    def test_zero_prev_spend_returns_one(self):
        eff = compute_reach_efficiency(1000, 1000, 100, 0)
        assert eff == pytest.approx(1.0)

    def test_zero_prev_eff_returns_one(self):
        eff = compute_reach_efficiency(1000, 0, 100, 100)
        assert eff == pytest.approx(1.0)


class TestClassifySaturation:
    def test_healthy_no_change(self):
        result = classify_saturation(1.0, 2.0, 2.0)
        assert result["stage"] == "healthy"

    def test_critical_on_large_drop(self):
        result = classify_saturation(0.1, 8.0, 2.0)
        assert result["stage"] == "critical"
        assert result["score"] > 0.65

    def test_warning_on_moderate_drop(self):
        result = classify_saturation(0.6, 4.0, 2.0)
        assert result["stage"] == "warning"

    def test_score_between_zero_and_one(self):
        for eff in [0.0, 0.5, 1.0, 2.0]:
            result = classify_saturation(eff, 3.0, 2.0)
            assert 0.0 <= result["score"] <= 1.0

    def test_result_contains_required_keys(self):
        result = classify_saturation(0.8, 2.5, 2.0)
        assert {"score", "stage", "reach_efficiency", "eff_score", "freq_score"} <= result.keys()

    def test_freq_score_caps_at_one(self):
        result = classify_saturation(1.0, 15.0, 1.0)
        assert result["freq_score"] <= 1.0


class TestDifferentiateDiagnosis:
    def test_creative_fatigue_low_ctr_high_reach(self):
        diag = differentiate_diagnosis(-0.30, 0.90, 2.0)
        assert diag == "creative_fatigue"

    def test_audience_saturation_low_reach_high_freq(self):
        diag = differentiate_diagnosis(0.0, 0.50, 5.0)
        assert diag == "audience_saturation"

    def test_market_shift_low_reach_low_freq(self):
        diag = differentiate_diagnosis(0.0, 0.50, 2.0)
        assert diag == "market_shift"

    def test_healthy(self):
        diag = differentiate_diagnosis(0.05, 0.95, 2.0)
        assert diag == "healthy"

    def test_valid_return_values(self):
        valid = {"creative_fatigue", "audience_saturation", "market_shift", "healthy"}
        for ctr in [-0.5, -0.1, 0.0, 0.1]:
            for eff in [0.4, 0.8]:
                for freq in [2.0, 5.0]:
                    assert differentiate_diagnosis(ctr, eff, freq) in valid
