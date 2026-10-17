"""Tests for reward_service.py (vision §19.3)."""
import pytest
from app.services.reward_service import (
    compute_stage_reward,
    compute_funnel_progress_score,
    estimate_quality_rate,
)

INVESTOR_STAGES = [
    {"stage_key": "FORM_SUBMIT",    "ordinal": 3,  "is_quality_key": False, "reward_weight": 0.3, "micro_reward_weight": 0.3},
    {"stage_key": "BOOKED",         "ordinal": 4,  "is_quality_key": False, "reward_weight": 0.5, "micro_reward_weight": 0.5},
    {"stage_key": "CALL_HELD",      "ordinal": 6,  "is_quality_key": True,  "reward_weight": 1.0, "micro_reward_weight": 0.0},
    {"stage_key": "NO_SHOW",        "ordinal": 7,  "is_quality_key": False, "reward_weight": 0.0, "micro_reward_weight": 0.0},
    {"stage_key": "FUNDS_RECEIVED", "ordinal": 11, "is_quality_key": False, "reward_weight": 1.0, "micro_reward_weight": 0.0},
]


def test_quality_stage_triggers_thompson():
    result = compute_stage_reward("CALL_HELD", False, INVESTOR_STAGES)
    assert result["thompson_increment"] == 1.0
    assert result["should_update_thompson"] is True


def test_non_quality_stage_no_thompson():
    result = compute_stage_reward("FORM_SUBMIT", False, INVESTOR_STAGES)
    assert result["thompson_increment"] == 0.0
    assert result["should_update_thompson"] is False


def test_is_quality_stage_override():
    """is_quality_stage=True overrides the stage's is_quality_key flag."""
    result = compute_stage_reward("BOOKED", True, INVESTOR_STAGES)
    assert result["thompson_increment"] == 1.0
    assert result["should_update_thompson"] is True


def test_funnel_progress_score():
    """Session reached BOOKED (ordinal 4) out of max 11 → score = 4/11."""
    result = compute_funnel_progress_score(["FORM_SUBMIT", "BOOKED"], INVESTOR_STAGES)
    assert result["deepest_stage"] == "BOOKED"
    assert abs(result["score"] - 4 / 11) < 0.001
    assert result["stages_reached"] == 2


def test_progress_score_empty():
    result = compute_funnel_progress_score([], INVESTOR_STAGES)
    assert result["score"] == 0.0
    assert result["deepest_stage"] is None


def test_progress_score_no_stages():
    result = compute_funnel_progress_score(["BOOKED"], [])
    assert result["score"] == 0.0


def test_estimate_quality_rate():
    """10 quality hits / 20 total → mean ≈ 11/22 ≈ 0.5."""
    events = (
        [{"variant_id": "v1", "is_quality_stage": True,  "rewarded_at": None}] * 10 +
        [{"variant_id": "v1", "is_quality_stage": False, "rewarded_at": None}] * 10
    )
    result = estimate_quality_rate(events, "v1")
    assert result["quality_hits"] == 10
    assert result["total"] == 20
    assert abs(result["mean"] - 11 / 22) < 0.01
    assert result["ci_80_lower"] < result["mean"] < result["ci_80_upper"]


def test_estimate_quality_rate_wrong_variant():
    events = [{"variant_id": "v2", "is_quality_stage": True, "rewarded_at": None}] * 5
    result = estimate_quality_rate(events, "v1")
    assert result["total"] == 0
    assert result["quality_hits"] == 0
