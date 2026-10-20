"""Tests for quality_service.py"""
import pytest
from app.services.quality_service import (
    DEFAULT_WEIGHTS,
    aggregate_quality_scores,
    compute_clickbait_check,
    compute_quality_score,
)


def test_quality_score_perfect():
    score = compute_quality_score(1.0, False, 1.0, 1.0)
    assert score == 1.0


def test_quality_score_zero():
    score = compute_quality_score(0.0, True, 0.0, 0.0)
    assert score == 0.0


def test_quality_score_none_match_uses_neutral_prior():
    score_none = compute_quality_score(None, False, 1.0, 1.0)
    score_half = compute_quality_score(0.5, False, 1.0, 1.0)
    assert score_none == score_half


def test_quality_score_equal_weights():
    score = compute_quality_score(0.5, False, 0.5, 0.5)
    expected = 0.25 * 0.5 + 0.25 * 1.0 + 0.25 * 0.5 + 0.25 * 0.5
    assert abs(score - round(expected, 4)) < 1e-4


def test_quality_score_clamped():
    score = compute_quality_score(1.5, False, 1.5, 1.5)
    assert score <= 1.0
    score_neg = compute_quality_score(-1.0, True, -1.0, -1.0)
    assert score_neg >= 0.0


def test_clickbait_clean_when_low_ctr_advantage():
    result = compute_clickbait_check(
        creative_ctr=0.03, anchor_ctr=0.03,
        creative_cvr=0.05, anchor_cvr=0.04,
        creative_bounce=0.5, anchor_bounce=0.5,
        ctr_n=1000, cvr_n=1000, bounce_n=1000,
    )
    assert result["suspect"] is False
    assert result["verdict"] == "CLEAN"


def test_clickbait_suspect_high_ctr_low_cvr():
    result = compute_clickbait_check(
        creative_ctr=0.15, anchor_ctr=0.03,
        creative_cvr=0.01, anchor_cvr=0.08,
        creative_bounce=0.3, anchor_bounce=0.3,
        ctr_n=5000, cvr_n=5000, bounce_n=5000,
    )
    assert result["suspect"] is True
    assert result["verdict"] == "CLICKBAIT_SUSPECT"
    assert result["p_ctr_higher_than_anchor"] > 0.9


def test_clickbait_insufficient_data_returns_clean():
    result = compute_clickbait_check(
        creative_ctr=0.15, anchor_ctr=0.03,
        creative_cvr=0.01, anchor_cvr=0.08,
        creative_bounce=0.3, anchor_bounce=0.3,
        ctr_n=3, cvr_n=3, bounce_n=3,
    )
    assert result["suspect"] is False


def test_aggregate_empty_sessions():
    result = aggregate_quality_scores([], {})
    assert result == {"by_creative": {}, "by_variant": {}}


def test_aggregate_groups_by_variant():
    sessions = [
        {"variant_id": "v1", "creative_id": "c1", "converted": True, "max_scroll_depth": 80, "event_count": 5},
        {"variant_id": "v1", "creative_id": "c1", "converted": False, "max_scroll_depth": 20, "event_count": 1},
    ]
    result = aggregate_quality_scores(sessions, {"v1": 0.7})
    assert "v1" in result["by_variant"]
    assert "c1" in result["by_creative"]
    assert 0.0 <= result["by_variant"]["v1"] <= 1.0


# ─── Roadmap 8-3: show_rate component + no-show driver in the clickbait detector ───

def test_show_rate_gets_highest_weight_when_present():
    from app.services.quality_service import APPOINTMENT_WEIGHTS
    held = compute_quality_score(0.5, False, 0.5, 1.0, show_rate=1.0)
    noshow = compute_quality_score(0.5, False, 0.5, 1.0, show_rate=0.0)
    assert abs((held - noshow) - APPOINTMENT_WEIGHTS["show_rate"]) < 1e-6
    assert APPOINTMENT_WEIGHTS["show_rate"] == max(APPOINTMENT_WEIGHTS.values())


def test_unresolved_booking_uses_base_weights():
    assert compute_quality_score(1.0, False, 1.0, 1.0) == compute_quality_score(1.0, False, 1.0, 1.0, show_rate=None) == 1.0


def test_noshow_driver_hook_is_clickbait_even_with_normal_cvr():
    res = compute_clickbait_check(0.05, 0.02, 0.03, 0.03, 0.4, 0.4, 5000, 5000, 5000, noshow_beta=-0.9)
    assert res["suspect"] is True and res["noshow_driver"] is True


def test_noshow_beta_above_threshold_does_not_flag():
    res = compute_clickbait_check(0.05, 0.02, 0.03, 0.03, 0.4, 0.4, 5000, 5000, 5000, noshow_beta=0.2)
    assert res["noshow_driver"] is False and res["suspect"] is False
