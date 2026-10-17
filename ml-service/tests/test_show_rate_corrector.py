"""Tests for show-rate corrector (roadmap 8-3)."""
import pytest
from app.services.reward_service import compute_show_rate_corrector, apply_show_rate_to_gates


def test_no_bookings_returns_neutral():
    result = compute_show_rate_corrector(booked_count=0, held_count=0)
    assert result["multiplier"] == 1.0
    assert result["show_rate_obs"] is None
    assert "No bookings" in result["note"]


def test_perfect_show_rate_boosts_multiplier():
    result = compute_show_rate_corrector(booked_count=10, held_count=10)
    assert result["multiplier"] > 1.0
    assert result["show_rate_obs"] == 1.0


def test_zero_show_rate_hits_floor():
    # Large sample overwhelms prior → posterior near 0 → multiplier at floor 0.5
    result = compute_show_rate_corrector(booked_count=200, held_count=0)
    assert result["multiplier"] == 0.5
    assert result["show_rate_obs"] == 0.0


def test_prior_show_rate_returns_neutral():
    # Prior alpha=3, beta=2 → mean=0.6. If observed exactly 6/10, multiplier ≈ 1.0
    result = compute_show_rate_corrector(booked_count=10, held_count=6)
    assert 0.95 <= result["multiplier"] <= 1.05


def test_multiplier_capped_at_1_5():
    # 100% show rate on 100 bookings — should not exceed 1.5
    result = compute_show_rate_corrector(booked_count=100, held_count=100)
    assert result["multiplier"] <= 1.5


def test_multiplier_capped_at_0_5():
    # 0% show rate on 100 bookings — should not go below 0.5
    result = compute_show_rate_corrector(booked_count=100, held_count=0)
    assert result["multiplier"] >= 0.5


def test_apply_to_gates_tightens_on_bad_show_rate():
    result = apply_show_rate_to_gates(l1_threshold=0.2, l2_threshold=0.4, multiplier=0.8)
    assert result["l1_adjusted"] > result["l1_original"]
    assert result["l2_adjusted"] > result["l2_original"]
    assert result["multiplier"] == 0.8


def test_apply_to_gates_loosens_on_good_show_rate():
    result = apply_show_rate_to_gates(l1_threshold=0.2, l2_threshold=0.4, multiplier=1.2)
    assert result["l1_adjusted"] < result["l1_original"]
    assert result["l2_adjusted"] < result["l2_original"]


def test_apply_to_gates_neutral():
    result = apply_show_rate_to_gates(l1_threshold=0.2, l2_threshold=0.4, multiplier=1.0)
    assert abs(result["l1_adjusted"] - 0.2) < 1e-9
    assert abs(result["l2_adjusted"] - 0.4) < 1e-9
