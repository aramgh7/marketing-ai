import pytest
from app.services.red_flags_service import (
    compute_beta_exceedance, get_bounce_threshold, check_red_flag,
)


def test_not_landing_no_kill():
    result = check_red_flag("lp-1", 80, 100, is_landing=False)
    assert result["should_kill"] is False


def test_insufficient_visits():
    result = check_red_flag("lp-1", 20, 30, is_landing=True)
    assert result["should_kill"] is False
    assert "Insufficient" in result["reason"]


def test_high_bounce_kills():
    # 90% bounce, 80 visits, threshold 0.70 → should kill
    result = check_red_flag("lp-1", bounced=72, total_visits=80,
                             niche_benchmark=None, is_landing=True)
    # threshold=0.75 (universal fallback), P(bounce>0.75 | 90%) ≈ high
    assert result["should_kill"] is True


def test_low_bounce_ok():
    result = check_red_flag("lp-1", bounced=20, total_visits=80, is_landing=True)
    assert result["should_kill"] is False


def test_threshold_universal_fallback():
    t = get_bounce_threshold(None)
    assert t["threshold"] == 0.75
    assert t["label"] == "heuristic"
    assert t["source"] == "UNIVERSAL_FALLBACK"


def test_threshold_niche_calibrated():
    bm = {"effect_size": 0.55, "ci_lower": 0.45, "ci_upper": 0.65, "is_published": True}
    t = get_bounce_threshold(bm)
    assert t["label"] == "niche_calibrated"
    assert t["source"] == "NICHE_BENCHMARK"
    assert t["threshold"] > 0.55


def test_threshold_unpublished_benchmark_fallback():
    bm = {"effect_size": 0.55, "ci_lower": 0.45, "ci_upper": 0.65, "is_published": False}
    t = get_bounce_threshold(bm)
    assert t["label"] == "heuristic"


def test_p_exceed_range():
    for bounced, total in [(0, 100), (50, 100), (100, 100)]:
        p = compute_beta_exceedance(bounced, total, 0.75)
        assert 0.0 <= p <= 1.0


def test_beta_exceedance_all_bounce():
    # 99 bounces out of 100 — P(bounce > 0.75) should be close to 1.0
    p = compute_beta_exceedance(99, 100, 0.75)
    assert p > 0.9


def test_beta_exceedance_no_bounce():
    # 0 bounces out of 100 — P(bounce > 0.75) should be close to 0.0
    p = compute_beta_exceedance(0, 100, 0.75)
    assert p < 0.05


def test_beta_exceedance_zero_trials():
    p = compute_beta_exceedance(0, 0, 0.75)
    assert p == 0.0


def test_check_red_flag_structure():
    result = check_red_flag("lp-x", 40, 60, is_landing=True)
    for key in ("landing_id", "should_kill", "p_exceed", "threshold", "label", "reason"):
        assert key in result
