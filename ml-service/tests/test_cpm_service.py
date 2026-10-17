"""Tests for cpm_service — 11 unit tests."""
import pytest
from app.services.cpm_service import predict_cpm, check_lgb_divergence


# ── predict_cpm tests ─────────────────────────────────────────────────────────

def test_predict_cpm_returns_dict():
    result = predict_cpm([], "FEED", [], 0.0)
    assert isinstance(result, dict)
    for key in ("cpm", "ci_lower", "ci_upper", "lgb_cpm", "divergence_pct", "flag_review"):
        assert key in result


def test_predict_cpm_positive_cpm():
    result = predict_cpm(["HOOK", "FACE"], "FEED", [], 0.0)
    assert result["cpm"] > 0


def test_predict_cpm_ci_order():
    result = predict_cpm(["HOOK"], "FEED", [], 0.0)
    assert result["ci_lower"] <= result["cpm"] <= result["ci_upper"]


def test_predict_cpm_no_atoms():
    result = predict_cpm([], "FEED", [], 0.0)
    assert result["cpm"] > 0
    assert result["ci_lower"] > 0


def test_predict_cpm_placement_stories_lower_than_feed():
    feed = predict_cpm([], "FEED", [], 0.0, n_samples=2000)
    stories = predict_cpm([], "STORIES", [], 0.0, n_samples=2000)
    assert stories["cpm"] < feed["cpm"]


def test_predict_cpm_q4_peak_higher():
    base = predict_cpm([], "FEED", [], 0.0, n_samples=2000)
    q4 = predict_cpm([], "FEED", ["Q4_PEAK"], 0.0, n_samples=2000)
    assert q4["cpm"] > base["cpm"]


def test_predict_cpm_cpm_index_positive_effect():
    low = predict_cpm([], "FEED", [], 0.0, n_samples=2000)
    high = predict_cpm([], "FEED", [], 1.0, n_samples=2000)
    assert high["cpm"] > low["cpm"]


def test_predict_cpm_divergence_pct_nonnegative():
    result = predict_cpm([], "FEED", [], 0.0)
    assert result["divergence_pct"] >= 0.0


def test_predict_cpm_flag_review_is_bool():
    result = predict_cpm([], "FEED", [], 0.0)
    assert isinstance(result["flag_review"], bool)


# ── check_lgb_divergence tests ────────────────────────────────────────────────

def test_check_lgb_divergence_low():
    result = check_lgb_divergence(20.0, 21.0)  # 5% divergence
    assert result["divergence_pct"] < 0.30
    assert result["flag_review"] is False


def test_check_lgb_divergence_high():
    result = check_lgb_divergence(20.0, 30.0)  # 50% divergence
    assert result["divergence_pct"] > 0.30
    assert result["flag_review"] is True


def test_check_lgb_divergence_exact_threshold():
    # 31% divergence → flag_review = True (strictly greater than 30%)
    bayes, lgb = 100.0, 131.0
    result = check_lgb_divergence(bayes, lgb)
    assert result["flag_review"] is True


def test_check_lgb_divergence_zero_bayes():
    result = check_lgb_divergence(0.0, 10.0)
    assert result["flag_review"] is False
