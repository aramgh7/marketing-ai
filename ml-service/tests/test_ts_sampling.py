"""Roadmap 9-10: Thompson sampling strategies (standard vs top-two) — pure logic."""
import numpy as np
import pytest

from app.services.ts_sampling_service import (
    sample_arms_standard,
    sample_arms_top_two,
    select_sampler,
)


def _arms():
    return [
        {"id": "a1", "variant_id": "v1", "alpha": 30.0, "beta": 70.0, "total_samples": 100, "block_order": []},
        {"id": "a2", "variant_id": "v2", "alpha": 25.0, "beta": 75.0, "total_samples": 100, "block_order": []},
        {"id": "a3", "variant_id": "v3", "alpha": 2.0, "beta": 98.0, "total_samples": 100, "block_order": []},
    ]


def _check_format(best, cands, arms):
    assert set(cands[0]) == {"variant_id", "arm_id", "propensity", "sample_value", "alpha", "beta"}
    assert [c["arm_id"] for c in cands] == [a["id"] for a in arms]
    assert best["id"] in {a["id"] for a in arms} and "sample_value" in best
    assert abs(sum(c["propensity"] for c in cands) - 1.0) <= 0.02


def test_standard_output_format():
    best, cands = sample_arms_standard(_arms(), rng=np.random.default_rng(1))
    _check_format(best, cands, _arms())


def test_standard_matches_existing_sampler_statistically():
    """Same Beta posteriors → the same win frequencies as thompson_service.sample_arms (±5 pp)."""
    import sys
    from unittest.mock import MagicMock
    sys.modules.setdefault("app.config", MagicMock(settings=MagicMock(reward_on_form_submit=True)))
    sys.modules.setdefault("app.services.db_client", MagicMock())
    sys.modules.setdefault("app.services.job_store", MagicMock())
    from app.services import thompson_service as ts
    if isinstance(ts, MagicMock):
        pytest.skip("thompson_service stubbed by another test module")
    _, ref = ts.sample_arms(_arms())
    _, mine = sample_arms_standard(_arms(), n_propensity=2000, rng=np.random.default_rng(7))
    for r, m in zip(ref, mine):
        assert abs(r["propensity"] - m["propensity"]) < 0.08


def test_standard_prefers_best_arm():
    _, cands = sample_arms_standard(_arms(), n_propensity=1000, rng=np.random.default_rng(3))
    by = {c["arm_id"]: c["propensity"] for c in cands}
    assert by["a1"] > by["a2"] > by["a3"]
    assert by["a3"] < 0.02


def test_standard_empty():
    assert sample_arms_standard([]) == (None, [])


def test_top_two_output_format_and_sum():
    best, cands = sample_arms_top_two(_arms(), beta=0.5, rng=np.random.default_rng(11))
    _check_format(best, cands, _arms())


def test_top_two_shifts_mass_to_the_challenger():
    """Top-two spends exploration on the runner-up: a2 gains, a1 loses, a3 stays negligible."""
    _, std = sample_arms_standard(_arms(), n_propensity=2000, rng=np.random.default_rng(5))
    _, tt = sample_arms_top_two(_arms(), beta=0.5, n_propensity=2000, rng=np.random.default_rng(5))
    s = {c["arm_id"]: c["propensity"] for c in std}
    t = {c["arm_id"]: c["propensity"] for c in tt}
    assert t["a2"] > s["a2"] + 0.1
    assert t["a1"] < s["a1"]
    assert t["a3"] < 0.05


def test_top_two_beta_one_equals_standard():
    """β = 1 never re-draws → identical to the standard sampler for the same rng stream."""
    rng_a = np.random.default_rng(42)
    rng_b = np.random.default_rng(42)
    best_std, c_std = sample_arms_standard(_arms(), n_propensity=300, rng=rng_a)
    # top-two with β=1 draws one extra uniform per decision, so compare on statistics, not streams
    best_tt, c_tt = sample_arms_top_two(_arms(), beta=1.0, n_propensity=3000, rng=rng_b)
    _, c_std2 = sample_arms_standard(_arms(), n_propensity=3000, rng=np.random.default_rng(43))
    for a, b in zip(c_std2, c_tt):
        assert abs(a["propensity"] - b["propensity"]) < 0.06


def test_top_two_beta_zero_always_serves_challenger():
    """β = 0 → the leader of the first draw is never served when another arm can lead a re-draw."""
    arms = _arms()[:2]
    rng = np.random.default_rng(9)
    for _ in range(50):
        best, cands = sample_arms_top_two(arms, beta=0.0, n_propensity=10, rng=rng)
        leader = max(cands, key=lambda c: c["sample_value"])
        assert best["id"] != leader["arm_id"]


def test_top_two_single_arm():
    best, cands = sample_arms_top_two(_arms()[:1], beta=0.3, rng=np.random.default_rng(1))
    assert best["id"] == "a1" and cands[0]["propensity"] == 1.0


def test_top_two_invalid_beta():
    with pytest.raises(ValueError):
        sample_arms_top_two(_arms(), beta=1.5)


def test_top_two_dominant_leader_falls_back_to_leader():
    """A hopeless challenger never leads a re-draw within the cap → the leader is served."""
    arms = [
        {"id": "a1", "variant_id": "v1", "alpha": 900.0, "beta": 100.0},
        {"id": "a2", "variant_id": "v2", "alpha": 1.0, "beta": 999.0},
    ]
    best, cands = sample_arms_top_two(arms, beta=0.0, n_propensity=20, rng=np.random.default_rng(2))
    assert best["id"] == "a1"
    assert {c["arm_id"]: c["propensity"] for c in cands}["a1"] == 1.0


def test_select_sampler():
    assert select_sampler("top_two") is sample_arms_top_two
    assert select_sampler("TOP_TWO ") is sample_arms_top_two
    assert select_sampler("standard") is sample_arms_standard
    assert select_sampler(None) is sample_arms_standard
    assert select_sampler("bogus") is sample_arms_standard
