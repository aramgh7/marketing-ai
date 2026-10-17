"""Tests for attribution_service.py — Phase 7 Cross-Channel MTA."""
import pytest
from app.services.attribution_service import (
    compute_markov_attribution,
    compute_mta_summary,
    compute_removal_effects,
    compute_shapley_attribution,
    compute_transition_matrix,
)


# ─── last_touch / linear helpers via compute_mta_summary ─────────────────────

def test_last_touch_attribution():
    paths = [
        ["META", "GOOGLE", "convert"],
        ["META", "GOOGLE", "convert"],
    ]
    result = compute_mta_summary(paths, method="last_touch")
    assert result["attribution"].get("GOOGLE", 0.0) == pytest.approx(1.0)
    assert result["attribution"].get("META", 0.0) == pytest.approx(0.0)


def test_linear_attribution():
    paths = [["META", "GOOGLE", "convert"]]
    result = compute_mta_summary(paths, method="linear")
    assert result["attribution"]["META"] == pytest.approx(0.5, abs=1e-5)
    assert result["attribution"]["GOOGLE"] == pytest.approx(0.5, abs=1e-5)


# ─── Markov ──────────────────────────────────────────────────────────────────

def test_markov_single_channel():
    paths = [["META", "convert"], ["META", "convert"], ["META", "convert"]]
    result = compute_markov_attribution(paths)
    assert "META" in result
    assert result["META"] == pytest.approx(1.0, abs=0.01)


def test_markov_two_channels_sum_to_one():
    paths = [
        ["META", "GOOGLE", "convert"],
        ["META", "convert"],
        ["GOOGLE", "META", "convert"],
        ["GOOGLE", "convert"],
    ]
    result = compute_markov_attribution(paths)
    total = sum(result.values())
    assert total == pytest.approx(1.0, abs=0.01)


def test_markov_converts_fraction():
    """All paths convert — should work without error."""
    paths = [["META", "convert"], ["GOOGLE", "convert"]]
    result = compute_markov_attribution(paths)
    assert isinstance(result, dict)
    assert len(result) == 2


def test_markov_no_converting_paths():
    paths = [["META", "GOOGLE"], ["TIKTOK"]]
    result = compute_markov_attribution(paths)
    # No converting paths → all zeros or empty dict
    total = sum(result.values())
    assert total == pytest.approx(0.0)


# ─── Shapley ─────────────────────────────────────────────────────────────────

def test_shapley_single_channel():
    paths = [["META", "convert"], ["META", "convert"]]
    result = compute_shapley_attribution(paths)
    assert "META" in result
    assert result["META"] == pytest.approx(1.0, abs=0.01)


def test_shapley_two_channels_sum_to_one():
    paths = [
        ["META", "GOOGLE", "convert"],
        ["META", "convert"],
        ["GOOGLE", "convert"],
        ["META", "GOOGLE", "convert"],
    ]
    result = compute_shapley_attribution(paths)
    total = sum(result.values())
    assert total == pytest.approx(1.0, abs=0.05)


def test_shapley_symmetric_channels():
    """Same channels appearing equally should get equal attribution."""
    paths = [
        ["A", "convert"], ["B", "convert"],
        ["A", "convert"], ["B", "convert"],
        ["A", "B", "convert"], ["B", "A", "convert"],
    ]
    result = compute_shapley_attribution(paths)
    if "A" in result and "B" in result:
        assert abs(result["A"] - result["B"]) < 0.15


# ─── compute_mta_summary ─────────────────────────────────────────────────────

def test_mta_summary_method_field():
    paths = [["META", "convert"]]
    result = compute_mta_summary(paths, method="shapley")
    assert result["method"] == "shapley"


def test_mta_summary_total_paths():
    paths = [["META", "convert"], ["GOOGLE"], ["META", "GOOGLE", "convert"]]
    result = compute_mta_summary(paths, method="markov")
    assert result["total_paths"] == 3


def test_mta_summary_converting_paths():
    paths = [["META", "convert"], ["GOOGLE"], ["META", "GOOGLE", "convert"]]
    result = compute_mta_summary(paths, method="markov")
    assert result["converting_paths"] == 2


def test_mta_summary_attribution_sums_to_one():
    paths = [
        ["META", "GOOGLE", "convert"],
        ["META", "convert"],
        ["GOOGLE", "convert"],
    ]
    result = compute_mta_summary(paths, method="markov")
    if result["converting_paths"] > 0:
        total = sum(result["attribution"].values())
        assert total == pytest.approx(1.0, abs=0.05)


# ─── compute_transition_matrix ───────────────────────────────────────────────

def test_compute_transition_matrix_from_single_path():
    paths = [["META", "GOOGLE", "convert"]]
    matrix = compute_transition_matrix(paths, channels=["META", "GOOGLE"])
    assert "META" in matrix
    assert "GOOGLE" in matrix["META"]
    assert matrix["META"]["GOOGLE"] == pytest.approx(1.0)


# ─── compute_removal_effects ─────────────────────────────────────────────────

def test_compute_removal_effect_not_negative():
    paths = [
        ["META", "GOOGLE", "convert"],
        ["META", "convert"],
        ["GOOGLE", "convert"],
    ]
    channels = ["META", "GOOGLE"]
    matrix = compute_transition_matrix(paths, channels)
    effects = compute_removal_effects(matrix, channels)
    for ch, val in effects.items():
        assert val >= 0.0, f"Removal effect for {ch} is negative: {val}"
