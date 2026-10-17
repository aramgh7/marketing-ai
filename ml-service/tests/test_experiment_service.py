import random
import pytest
from app.services.experiment_service import (
    estimate_ess,
    thompson_best_arm,
    should_conclude,
    check_auto_pause,
    arm_worse_than_control,
)


def make_arm(vid, alpha, beta, is_control=False):
    return {"variant_id": vid, "alpha": alpha, "beta": beta, "is_control": is_control}


def test_thompson_best_arm_returns_all():
    arms = [make_arm("a", 10, 5), make_arm("b", 5, 10), make_arm("c", 7, 7)]
    result = thompson_best_arm(arms, n_samples=2000)
    assert len(result) == 3
    total_prob = sum(r["win_probability"] for r in result)
    assert abs(total_prob - 1.0) < 0.01
    # first result is highest win_probability
    assert result[0]["win_probability"] >= result[1]["win_probability"]


def test_should_conclude_insufficient_ess():
    arms = [make_arm("a", 2, 2), make_arm("b", 2, 2)]  # ESS = 0+0 = 0
    result = should_conclude(arms, min_ess=200)
    assert result["should_conclude"] is False
    assert "ESS" in result["reason"]


def test_should_conclude_clear_winner():
    # arm A has alpha=150, beta=2 — very strong
    # arm B has alpha=5, beta=5 — weak
    arms = [make_arm("a", 150, 2), make_arm("b", 5, 5)]
    result = should_conclude(arms, min_ess=100, auto_stop_p=0.95)
    assert result["should_conclude"] is True
    assert result["winner_variant_id"] == "a"


def test_estimate_ess():
    arms = [make_arm("a", 51, 51)]  # alpha+beta-2 = 100
    assert estimate_ess(arms) == pytest.approx(100.0)


def test_check_auto_pause_insufficient_ess():
    arms = [
        make_arm("ctrl", 40, 15, is_control=True),
        make_arm("b", 2, 3),  # ESS = 3 < 50
    ]
    result = check_auto_pause(arms, min_ess_pause=50, p_threshold=0.95)
    assert len(result) == 1
    assert result[0]["should_pause"] is False
    assert result[0]["p_worse"] is None


def test_check_auto_pause_clear_loser():
    # arm B: alpha=3, beta=47 (show_rate~0.06) vs control alpha=40, beta=15 (show_rate~0.73)
    # P(B < ctrl) should be very high
    arms = [
        make_arm("ctrl", 40, 15, is_control=True),
        make_arm("b", 3, 47),
    ]
    # arm_ess = 3+47-2 = 48 — just under 50
    # increase to ess=52 to cross threshold
    arms[1] = make_arm("b", 4, 50)  # ess=52
    result = check_auto_pause(arms, min_ess_pause=50, p_threshold=0.95)
    assert len(result) == 1
    assert result[0]["should_pause"] is True
    assert result[0]["p_worse"] > 0.95


def test_no_control_arm_returns_empty():
    arms = [make_arm("a", 10, 5), make_arm("b", 8, 6)]
    result = check_auto_pause(arms)
    assert result == []
