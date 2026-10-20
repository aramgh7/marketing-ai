"""Roadmap 9-10: expected value of information for experiment ranking — pure logic."""
import numpy as np

from app.services.evi_service import expected_value_of_information, rank_experiments


def _arms():
    return [
        {"id": "sure", "variant_id": "v_sure", "alpha": 300.0, "beta": 700.0},      # mean 0.30, tight
        {"id": "unsure", "variant_id": "v_unsure", "alpha": 3.0, "beta": 7.0},      # mean 0.30, loose
        {"id": "dead", "variant_id": "v_dead", "alpha": 2.0, "beta": 198.0},        # mean 0.01
    ]


def test_uncertain_contender_ranks_above_certain_leader():
    rows = expected_value_of_information(_arms(), n_extra=100, n_mc=3000, rng=np.random.default_rng(1))
    assert rows[0]["arm_id"] == "unsure"
    by = {r["arm_id"]: r for r in rows}
    assert by["unsure"]["evi"] > by["sure"]["evi"] >= 0.0
    assert by["dead"]["evi"] < by["unsure"]["evi"]


def test_output_shape_and_probabilities():
    rows = expected_value_of_information(_arms(), n_mc=500, rng=np.random.default_rng(2))
    assert {"arm_id", "variant_id", "evi", "p_best_now", "p_best_after_mean", "mean_now"} <= set(rows[0])
    assert abs(sum(r["p_best_now"] for r in rows) - 1.0) < 1e-6
    assert all(0.0 <= r["p_best_after_mean"] <= 1.0 for r in rows)


def test_evi_non_negative_and_sorted():
    rows = expected_value_of_information(_arms(), n_mc=800, rng=np.random.default_rng(3))
    assert all(r["evi"] >= 0 for r in rows)
    assert [r["evi"] for r in rows] == sorted((r["evi"] for r in rows), reverse=True)


def test_more_extra_sessions_more_information():
    small = expected_value_of_information(_arms(), n_extra=10, n_mc=3000, rng=np.random.default_rng(4))
    large = expected_value_of_information(_arms(), n_extra=500, n_mc=3000, rng=np.random.default_rng(4))
    s = {r["arm_id"]: r["evi"] for r in small}
    l = {r["arm_id"]: r["evi"] for r in large}
    assert l["unsure"] >= s["unsure"]


def test_empty_and_single_arm():
    assert expected_value_of_information([]) == []
    rows = expected_value_of_information(_arms()[:1], n_mc=200, rng=np.random.default_rng(5))
    assert len(rows) == 1 and rows[0]["p_best_now"] == 1.0


def test_rank_experiments_budget_floor_and_sum():
    rows = expected_value_of_information(_arms(), n_mc=1000, rng=np.random.default_rng(6))
    alloc = rank_experiments(rows, budget_sessions=1000)
    assert sum(a["sessions"] for a in alloc) == 1000
    assert all(a["budget_share"] >= 0.05 - 1e-9 for a in alloc)
    assert abs(sum(a["budget_share"] for a in alloc) - 1.0) < 1e-6
    assert alloc[0]["arm_id"] == "unsure"


def test_rank_experiments_zero_evi_equal_split():
    cands = [{"arm_id": "a", "evi": 0.0}, {"arm_id": "b", "evi": 0.0}]
    alloc = rank_experiments(cands, 99)
    assert sorted(a["sessions"] for a in alloc) == [49, 50]
    assert rank_experiments([], 10) == [] and rank_experiments(cands, 0) == []
