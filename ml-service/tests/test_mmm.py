"""Tests for mmm_service.py — Phase 7 Bayesian MMM."""
import pytest
from app.services.mmm_service import (
    apply_adstock,
    apply_saturation,
    build_mmm_summary,
    compute_channel_contributions,
    optimize_budget_allocation,
)


# ─── adstock ─────────────────────────────────────────────────────────────────

def test_adstock_zero_decay():
    spends = [100.0, 200.0, 150.0]
    result = apply_adstock(spends, decay_rate=0.0)
    assert result == spends


def test_adstock_full_decay():
    spends = [100.0, 0.0, 0.0]
    result = apply_adstock(spends, decay_rate=1.0)
    assert result[0] == pytest.approx(100.0)
    assert result[1] == pytest.approx(100.0)
    assert result[2] == pytest.approx(100.0)


def test_adstock_partial_decay():
    spends = [100.0, 0.0, 0.0]
    result = apply_adstock(spends, decay_rate=0.5)
    assert result[0] == pytest.approx(100.0)
    assert result[1] == pytest.approx(50.0)
    assert result[2] == pytest.approx(25.0)


def test_adstock_length_preserved():
    spends = [10.0, 20.0, 30.0, 40.0, 50.0]
    result = apply_adstock(spends, decay_rate=0.3)
    assert len(result) == len(spends)


def test_adstock_empty():
    assert apply_adstock([], 0.5) == []


# ─── saturation ──────────────────────────────────────────────────────────────

def test_saturation_at_half():
    # f(hs) = hs^1 / (hs^1 + hs^1) = 0.5
    result = apply_saturation([1000.0], half_saturation=1000.0, slope=1.0)
    assert result[0] == pytest.approx(0.5)


def test_saturation_approaches_one():
    result = apply_saturation([1_000_000.0], half_saturation=100.0, slope=1.0)
    assert result[0] > 0.999


def test_saturation_zero_input():
    result = apply_saturation([0.0, 0.0], half_saturation=500.0)
    assert result == [0.0, 0.0]


def test_saturation_length_preserved():
    data = [100.0, 200.0, 300.0]
    result = apply_saturation(data, half_saturation=250.0)
    assert len(result) == len(data)


def test_saturation_empty():
    assert apply_saturation([], half_saturation=1000.0) == []


# ─── contributions ───────────────────────────────────────────────────────────

def test_compute_contributions_returns_all_channels():
    spend_data = {"META": [1000.0, 1200.0], "GOOGLE": [500.0, 600.0]}
    contributions = {"META": 0.7, "GOOGLE": 0.3}
    result = compute_channel_contributions(spend_data, contributions)
    assert set(result.keys()) == {"META", "GOOGLE"}


def test_compute_contributions_values():
    spend_data = {"META": [1000.0], "GOOGLE": [500.0]}
    contributions = {"META": 0.6, "GOOGLE": 0.4}
    result = compute_channel_contributions(spend_data, contributions)
    assert result["META"][0] == pytest.approx(600.0)
    assert result["GOOGLE"][0] == pytest.approx(200.0)


def test_compute_contributions_empty_spends():
    result = compute_channel_contributions({}, {})
    assert result == {}


# ─── optimize ────────────────────────────────────────────────────────────────

def test_optimize_respects_total_budget():
    params = {
        "META":   {"half_saturation": 5000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 2.0},
        "GOOGLE": {"half_saturation": 3000.0, "slope": 1.0, "decay_rate": 0.4, "roi_scale": 1.5},
    }
    result = optimize_budget_allocation(params, total_budget=10000.0)
    total = sum(result["allocation"].values())
    assert total == pytest.approx(10000.0, abs=1.0)


def test_optimize_returns_expected_roi():
    params = {
        "META": {"half_saturation": 5000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 2.0},
    }
    result = optimize_budget_allocation(params, total_budget=5000.0)
    assert "expected_roi" in result
    assert result["expected_roi"] >= 0.0


def test_optimize_more_budget_to_less_saturated_channel():
    """GOOGLE has lower half_saturation = more saturated at same budget → META gets more."""
    params = {
        "META":   {"half_saturation": 10000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 1.0},
        "GOOGLE": {"half_saturation": 100.0,   "slope": 1.0, "decay_rate": 0.5, "roi_scale": 1.0},
    }
    result = optimize_budget_allocation(params, total_budget=1000.0, n_steps=10)
    assert result["allocation"]["META"] >= result["allocation"]["GOOGLE"]


def test_optimize_single_channel():
    params = {"META": {"half_saturation": 5000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 2.0}}
    result = optimize_budget_allocation(params, total_budget=5000.0)
    assert result["allocation"]["META"] == pytest.approx(5000.0)


def test_optimize_empty_params():
    result = optimize_budget_allocation({}, total_budget=5000.0)
    assert result["allocation"] == {}


# ─── build_mmm_summary ───────────────────────────────────────────────────────

def test_build_mmm_summary_keys():
    spend_data = {"META": [1000.0, 1200.0], "GOOGLE": [500.0, 600.0]}
    contributions = {"META": 0.7, "GOOGLE": 0.3}
    saturation_params = {
        "META":   {"half_saturation": 5000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 2.0},
        "GOOGLE": {"half_saturation": 3000.0, "slope": 1.0, "decay_rate": 0.4, "roi_scale": 1.5},
    }
    result = build_mmm_summary(spend_data, contributions, saturation_params, ("2026-07-01", "2026-09-28"))
    assert "channels" in result
    assert "contributions" in result
    assert "saturation_curves" in result
    assert "budget_allocation" in result
    assert "n_obs" in result
    assert "date_range_start" in result
    assert "date_range_end" in result


def test_build_mmm_summary_n_obs():
    spend_data = {"META": [1000.0, 1200.0, 1100.0], "GOOGLE": [500.0, 600.0, 550.0]}
    contributions = {"META": 0.7, "GOOGLE": 0.3}
    saturation_params = {
        "META":   {"half_saturation": 5000.0, "slope": 1.0, "decay_rate": 0.5, "roi_scale": 2.0},
        "GOOGLE": {"half_saturation": 3000.0, "slope": 1.0, "decay_rate": 0.4, "roi_scale": 1.5},
    }
    result = build_mmm_summary(spend_data, contributions, saturation_params, ("2026-07-01", "2026-09-28"))
    assert result["n_obs"] == 3
