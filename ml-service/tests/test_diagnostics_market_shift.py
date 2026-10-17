"""Tests for detect_market_shift() — Block F §5."""
import pytest
from app.services.diagnostics_service import detect_market_shift


def _market_symptoms(n: int = 3) -> list[dict]:
    return [{"symptom": "MARKET_SHIFT", "placement": f"FEED_{i}", "device": "MOBILE"} for i in range(n)]


def test_market_shift_detected_when_all_conditions_met():
    result = detect_market_shift(
        symptom_results=_market_symptoms(3),
        ctr_baseline=0.05,
        cvr_baseline=0.03,
        ctr_observed=0.03,   # -40% drop
        cvr_observed=0.018,  # -40% drop
    )
    assert result["market_shift_detected"] is True
    assert result["placements_affected"] == 3


def test_market_shift_not_detected_single_placement():
    result = detect_market_shift(
        symptom_results=_market_symptoms(1),  # only 1 placement
        ctr_baseline=0.05,
        cvr_baseline=0.03,
        ctr_observed=0.03,
        cvr_observed=0.018,
    )
    assert result["market_shift_detected"] is False


def test_market_shift_not_detected_small_ctr_drop():
    result = detect_market_shift(
        symptom_results=_market_symptoms(3),
        ctr_baseline=0.05,
        cvr_baseline=0.03,
        ctr_observed=0.048,  # only -4% drop — below 20% threshold
        cvr_observed=0.018,
    )
    assert result["market_shift_detected"] is False


def test_market_shift_not_detected_small_cvr_drop():
    result = detect_market_shift(
        symptom_results=_market_symptoms(3),
        ctr_baseline=0.05,
        cvr_baseline=0.03,
        ctr_observed=0.03,
        cvr_observed=0.029,  # only -3% drop — below 20% threshold
    )
    assert result["market_shift_detected"] is False


def test_market_shift_no_baseline():
    result = detect_market_shift(
        symptom_results=_market_symptoms(3),
        ctr_baseline=0.0,   # zero baseline
        cvr_baseline=0.03,
        ctr_observed=0.03,
        cvr_observed=0.018,
    )
    assert result["market_shift_detected"] is False
    assert "No baseline" in result["reason"]


def test_market_shift_reports_deltas():
    result = detect_market_shift(
        symptom_results=_market_symptoms(2),
        ctr_baseline=0.10,
        cvr_baseline=0.05,
        ctr_observed=0.06,   # -40%
        cvr_observed=0.02,   # -60%
    )
    assert result["ctr_delta_pct"] == pytest.approx(-40.0, abs=0.5)
    assert result["cvr_delta_pct"] == pytest.approx(-60.0, abs=0.5)


def test_market_shift_thresholds_in_output():
    result = detect_market_shift(
        symptom_results=_market_symptoms(2),
        ctr_baseline=0.05, cvr_baseline=0.03,
        ctr_observed=0.03, cvr_observed=0.018,
    )
    assert "thresholds" in result
    assert result["thresholds"]["ctr_drop_pct"] == -20.0
