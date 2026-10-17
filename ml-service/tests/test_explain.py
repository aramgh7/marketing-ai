"""Tests for explain_service.py"""
import pytest
from app.services.explain_service import _wilson_ci, build_explanation_context, format_explanation


def test_wilson_ci_zero_observations():
    lo, hi = _wilson_ci(0, 0)
    assert lo == 0.0
    assert hi == 1.0


def test_wilson_ci_perfect_conversion():
    lo, hi = _wilson_ci(100, 100)
    assert lo > 0.9
    assert hi >= 0.9999


def test_wilson_ci_no_conversions():
    lo, hi = _wilson_ci(0, 100)
    assert lo == 0.0
    assert hi < 0.1


def test_wilson_ci_bounds():
    lo, hi = _wilson_ci(50, 100)
    assert 0.0 <= lo <= hi <= 1.0


def test_build_explanation_context_variant():
    stats = {
        "alpha": 51.0, "beta": 51.0, "n": 100,
        "p_best": 0.75,
        "quality_score": 0.82,
        "anchor_cvr": 0.40,
        "external_ctx": None,
    }
    ctx = build_explanation_context("variant", stats)
    assert "cvr_mean" in ctx
    assert "anchor_comparison" in ctx
    assert "quality_score" in ctx
    assert abs(ctx["cvr_mean"] - 0.5) < 0.01


def test_build_explanation_context_creative():
    stats = {
        "alpha": 11.0, "beta": 91.0, "n": 100,
        "p_best": 0.3,
        "frequency": 4.2,
        "burnout_threshold": 7.0,
    }
    ctx = build_explanation_context("creative", stats)
    assert "frequency_vs_threshold" in ctx
    assert "4.2" in ctx["frequency_vs_threshold"]


def test_format_explanation_no_llm_returns_template():
    stats = {
        "alpha": 11.0, "beta": 91.0, "n": 100,
        "p_best": 0.3,
        "anchor_cvr": 0.08,
    }
    ctx = build_explanation_context("variant", stats)
    text = format_explanation("variant", ctx, llm_client=None)
    assert "conversion rate" in text.lower()
    assert "Note:" in text


def test_format_explanation_unknown_entity_returns_json():
    ctx = {"some_key": "some_val"}
    text = format_explanation("unknown_type", ctx, llm_client=None)
    assert "some_key" in text


def test_quality_score_missing_shows_not_computed():
    stats = {"alpha": 2.0, "beta": 10.0, "n": 10, "p_best": 0.2}
    ctx = build_explanation_context("variant", stats)
    assert "not yet computed" in ctx["quality_score"]
