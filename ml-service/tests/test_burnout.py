"""Tests for burnout_service.py — Phase 6-1."""
import pytest
from app.services.burnout_service import (
    compute_burnout_score,
    classify_burnout,
    estimate_days_remaining,
)


class TestClassifyBurnout:
    def test_none_below_threshold(self):
        assert classify_burnout(0.0) == "none"
        assert classify_burnout(0.34) == "none"

    def test_warning_band(self):
        assert classify_burnout(0.36) == "warning"
        assert classify_burnout(0.70) == "warning"

    def test_critical_above(self):
        assert classify_burnout(0.71) == "critical"
        assert classify_burnout(1.0) == "critical"

    def test_boundary_exactly_zero_point_35(self):
        assert classify_burnout(0.35) == "none"

    def test_boundary_exactly_zero_point_seven(self):
        assert classify_burnout(0.7) == "warning"


class TestComputeBurnoutScore:
    def test_healthy_creative(self):
        result = compute_burnout_score(
            frequency=1.2, ctr_current=0.05, ctr_baseline=0.05
        )
        assert result["score"] == 0.0
        assert result["stage"] == "none"

    def test_high_frequency_no_ctr_drop(self):
        result = compute_burnout_score(
            frequency=8.0, ctr_current=0.05, ctr_baseline=0.05
        )
        assert result["score"] > 0
        assert result["stage"] in ("warning", "critical")

    def test_critical_burnout(self):
        result = compute_burnout_score(
            frequency=7.0, ctr_current=0.01, ctr_baseline=0.05
        )
        assert result["stage"] == "critical"
        assert result["score"] > 0.7

    def test_warning_burnout(self):
        # frequency=4.0, ctr drop 40% → score ≈ 0.39 → warning
        result = compute_burnout_score(
            frequency=4.0, ctr_current=0.03, ctr_baseline=0.05
        )
        assert result["stage"] == "warning"

    def test_score_clamped_to_one(self):
        result = compute_burnout_score(
            frequency=20.0, ctr_current=0.0, ctr_baseline=0.1
        )
        assert result["score"] <= 1.0

    def test_score_clamped_to_zero(self):
        result = compute_burnout_score(
            frequency=0.0, ctr_current=0.1, ctr_baseline=0.05
        )
        assert result["score"] >= 0.0

    def test_ctr_baseline_zero_no_crash(self):
        result = compute_burnout_score(
            frequency=2.0, ctr_current=0.03, ctr_baseline=0.0
        )
        assert result["score"] >= 0.0

    def test_signals_emitted_on_high_frequency(self):
        result = compute_burnout_score(
            frequency=5.0, ctr_current=0.04, ctr_baseline=0.05
        )
        assert any("frequency" in s.lower() for s in result["signals"])

    def test_signals_emitted_on_ctr_drop(self):
        result = compute_burnout_score(
            frequency=1.5, ctr_current=0.01, ctr_baseline=0.05
        )
        assert any("ctr" in s.lower() or "%" in s for s in result["signals"])

    def test_spend_params_accepted(self):
        result = compute_burnout_score(
            frequency=2.0, ctr_current=0.04, ctr_baseline=0.05,
            spend_current=500.0, spend_prev=400.0
        )
        assert "score" in result

    def test_stage_none_when_healthy(self):
        result = compute_burnout_score(
            frequency=1.5, ctr_current=0.05, ctr_baseline=0.05
        )
        assert result["stage"] == "none"


class TestEstimateDaysRemaining:
    def test_zero_drop_rate_returns_max(self):
        assert estimate_days_remaining(2.0, 0.0) == 90

    def test_negative_drop_rate_returns_max(self):
        assert estimate_days_remaining(2.0, -0.01) == 90

    def test_fast_drop_returns_one(self):
        assert estimate_days_remaining(3.0, 1.0) == 1

    def test_reasonable_estimate(self):
        days = estimate_days_remaining(2.0, 0.01)
        assert 1 <= days <= 90

    def test_result_clamped_minimum(self):
        days = estimate_days_remaining(5.0, 100.0)
        assert days >= 1
