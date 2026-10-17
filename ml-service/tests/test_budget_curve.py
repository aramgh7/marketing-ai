"""Tests for budget_curve_service.py — Phase 6-3."""
import pytest
from app.services.budget_curve_service import (
    simulate_budget_step,
    compute_budget_curve,
    find_diminishing_returns_point,
)


class TestSimulateBudgetStep:
    def test_returns_required_keys(self):
        result = simulate_budget_step(5.0, 5.0, 10.0, 100.0, n_mc=200, rng_seed=42)
        assert {"budget", "cpa_p10", "cpa_p50", "cpa_p90"} <= result.keys()

    def test_budget_matches_input(self):
        result = simulate_budget_step(5.0, 5.0, 10.0, 500.0, n_mc=200, rng_seed=0)
        assert result["budget"] == 500.0

    def test_percentile_order(self):
        result = simulate_budget_step(5.0, 5.0, 10.0, 200.0, n_mc=500, rng_seed=1)
        assert result["cpa_p10"] <= result["cpa_p50"] <= result["cpa_p90"]

    def test_cpa_positive(self):
        result = simulate_budget_step(2.0, 8.0, 10.0, 100.0, n_mc=300, rng_seed=2)
        assert result["cpa_p50"] > 0

    def test_higher_alpha_lower_cpa(self):
        low_cvr  = simulate_budget_step(1.0, 19.0, 10.0, 100.0, n_mc=1000, rng_seed=3)
        high_cvr = simulate_budget_step(10.0, 1.0, 10.0, 100.0, n_mc=1000, rng_seed=3)
        assert high_cvr["cpa_p50"] < low_cvr["cpa_p50"]

    def test_zero_alpha_beta_fallback(self):
        result = simulate_budget_step(0.0, 0.0, 5.0, 100.0, n_mc=200, rng_seed=4)
        assert result["cpa_p50"] is not None

    def test_deterministic_with_seed(self):
        r1 = simulate_budget_step(3.0, 7.0, 10.0, 200.0, n_mc=500, rng_seed=99)
        r2 = simulate_budget_step(3.0, 7.0, 10.0, 200.0, n_mc=500, rng_seed=99)
        assert r1 == r2


class TestComputeBudgetCurve:
    def test_returns_list_same_length(self):
        points = [100, 200, 500, 1000]
        curve = compute_budget_curve(5.0, 5.0, 10.0, points, n_mc=200)
        assert len(curve) == len(points)

    def test_each_step_has_cpa(self):
        curve = compute_budget_curve(3.0, 7.0, 10.0, [50, 100, 200], n_mc=200)
        for step in curve:
            assert step["cpa_p50"] is not None

    def test_budget_values_match_input(self):
        points = [100.0, 300.0, 500.0]
        curve = compute_budget_curve(5.0, 5.0, 10.0, points, n_mc=200)
        assert [s["budget"] for s in curve] == points

    def test_empty_points_returns_empty(self):
        curve = compute_budget_curve(5.0, 5.0, 10.0, [], n_mc=200)
        assert curve == []


class TestFindDiminishingReturns:
    def test_detects_jump(self):
        curve = [
            {"budget": 100, "cpa_p50": 10.0},
            {"budget": 200, "cpa_p50": 11.0},
            {"budget": 500, "cpa_p50": 14.0},
        ]
        pt = find_diminishing_returns_point(curve)
        assert pt == 500

    def test_no_jump_returns_none(self):
        curve = [
            {"budget": 100, "cpa_p50": 10.0},
            {"budget": 200, "cpa_p50": 10.5},
            {"budget": 500, "cpa_p50": 11.0},
        ]
        pt = find_diminishing_returns_point(curve)
        assert pt is None

    def test_single_point_returns_none(self):
        assert find_diminishing_returns_point([{"budget": 100, "cpa_p50": 10.0}]) is None

    def test_none_cpa_skipped(self):
        curve = [
            {"budget": 100, "cpa_p50": 10.0},
            {"budget": 200, "cpa_p50": None},
            {"budget": 500, "cpa_p50": 20.0},
        ]
        pt = find_diminishing_returns_point(curve)
        assert pt == 500
