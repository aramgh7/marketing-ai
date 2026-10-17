"""Tests for geo_lift_service — 6 unit tests."""
import pytest
from app.services.geo_lift_service import (
    validate_geo_split,
    compute_synthetic_control_simple,
)


# ── validate_geo_split ────────────────────────────────────────────────────────

def test_validate_geo_split_valid():
    r = validate_geo_split(["US-CA", "US-NY"], ["US-TX"])
    assert r["valid"] is True
    assert r["errors"] == []


def test_validate_geo_split_overlap():
    r = validate_geo_split(["US-CA", "US-TX"], ["US-TX", "US-FL"])
    assert r["valid"] is False
    assert any("Overlapping" in e for e in r["errors"])


def test_validate_geo_split_empty_control():
    r = validate_geo_split([], ["US-TX"])
    assert r["valid"] is False


def test_validate_geo_split_single_control():
    r = validate_geo_split(["US-CA"], ["US-TX"])
    assert r["valid"] is False
    assert any("2 control" in e for e in r["errors"])


# ── compute_synthetic_control_simple ─────────────────────────────────────────

def test_synthetic_control_basic_lift():
    r = compute_synthetic_control_simple(
        pre_period_control=[100, 110, 105],
        pre_period_test=[50, 55, 52],
        post_period_control=[100, 110],
        post_period_test=[70, 75],   # test grows more than expected
    )
    assert r["lift_estimate"] is not None
    assert r["lift_estimate"] > 0
    assert r["ci_lower"] is not None
    assert r["ci_upper"] is not None
    assert r["p_positive"] > 0.5


def test_synthetic_control_zero_control_pre():
    r = compute_synthetic_control_simple([0, 0], [10, 10], [50], [60])
    assert r["lift_estimate"] is None
    assert "error" in r
