"""Roadmap 9-12: Markowitz mean-variance allocation from posterior ROAS samples — pure logic."""
import numpy as np

from app.services.portfolio_service import allocate, compute_markowitz


def _assets():
    rng = np.random.default_rng(0)
    return [
        {"id": "c_high", "roas_samples": list(rng.normal(3.0, 0.3, 500))},    # high, steady
        {"id": "c_mid", "roas_samples": list(rng.normal(2.5, 0.2, 500))},     # mid, very steady
        {"id": "c_wild", "roas_samples": list(rng.normal(3.2, 2.0, 500))},    # highest mean, huge variance
        {"id": "c_low", "roas_samples": list(rng.normal(1.0, 0.3, 500))},     # poor
    ]


def test_weights_sum_to_one_and_respect_floor():
    res = compute_markowitz(_assets(), rng=np.random.default_rng(1))
    w = res["weights"]
    assert abs(sum(w.values()) - 1.0) < 1e-3
    assert all(v >= 0.05 - 1e-6 for v in w.values())
    assert res["method"] == "markowitz" and res["is_recommendation_only"] is True


def test_poor_asset_sits_at_floor_and_steady_high_dominates():
    res = compute_markowitz(_assets(), risk_aversion=3.0, rng=np.random.default_rng(2))
    w = res["weights"]
    assert w["c_low"] <= 0.05 + 1e-3
    assert w["c_high"] > w["c_wild"]                 # variance penalised


def test_higher_risk_aversion_lowers_variance():
    cautious = compute_markowitz(_assets(), risk_aversion=10.0, rng=np.random.default_rng(3))
    bold = compute_markowitz(_assets(), risk_aversion=0.1, rng=np.random.default_rng(3))
    assert cautious["variance"] < bold["variance"]
    assert bold["expected_roas"] >= cautious["expected_roas"] - 1e-6


def test_beta_scaled_inputs_accepted():
    assets = [
        {"id": "a", "roas_alpha": 30, "roas_beta": 70, "roas_scale": 10.0},   # ROAS ≈ 3
        {"id": "b", "roas_alpha": 10, "roas_beta": 90, "roas_scale": 10.0},   # ROAS ≈ 1
    ]
    res = compute_markowitz(assets, rng=np.random.default_rng(4))
    assert res["weights"]["a"] > res["weights"]["b"]
    assert abs(res["mu"]["a"] - 3.0) < 0.3


def test_cbo_excluded_and_edge_cases():
    res = compute_markowitz([{"id": "x", "is_cbo": True, "roas_samples": [1, 2, 3]}])
    assert res["weights"] == {} and res["warnings"]
    single = compute_markowitz([{"id": "only", "roas_samples": [2.0, 2.5, 3.0]}], rng=np.random.default_rng(5))
    assert single["weights"] == {"only": 1.0} and single["variance"] == 0.0


def test_allocate_falls_back_to_sharpe_without_history():
    assets = [
        {"id": "a", "name": "A", "daily_returns": [0.1, 0.12, 0.11, 0.13], "roas_samples": [3.0, 3.1]},
        {"id": "b", "name": "B", "daily_returns": [0.01, -0.02, 0.0, 0.01], "roas_samples": [1.0, 1.1]},
    ]
    sharpe = allocate(assets, history_ok=False, total_budget=1000.0)
    assert sharpe["method"] == "sharpe" and abs(sum(sharpe["weights"].values()) - 1.0) < 1e-3
    mk = allocate(assets, history_ok=True, total_budget=1000.0, rng=np.random.default_rng(6))
    assert mk["method"] == "markowitz"
    assert abs(sum(a["budget"] for a in mk["allocations"]) - 1000.0) < 1.0
