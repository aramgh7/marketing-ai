import pytest
from app.services.diagnostics_service import (
    compute_z_score, z_to_p_value, benjamini_hochberg, check_minimum_n,
    run_diagnostics, compute_lpv_click_gap,
)


def test_z_score_positive():
    z = compute_z_score(0.05, 0.02, 0.01, 100)
    assert z > 0


def test_z_score_negative():
    z = compute_z_score(0.01, 0.05, 0.02, 100)
    assert z < 0


def test_z_score_zero_std():
    assert compute_z_score(0.05, 0.02, 0.0, 100) == 0.0


def test_z_to_p_value_range():
    for z in [-3.0, -1.0, 0.0, 1.0, 3.0]:
        p = z_to_p_value(z)
        assert 0.0 <= p <= 1.0


def test_bh_single_significant():
    # very small p-value should be significant
    flags = benjamini_hochberg([0.001], fdr=0.10)
    assert flags == [True]


def test_bh_single_not_significant():
    flags = benjamini_hochberg([0.9], fdr=0.10)
    assert flags == [False]


def test_bh_multiple_correction():
    # 5 p-values all at 0.04 at FDR=0.10: the largest rank k with p(k) <= 0.10*k/5 is k=5
    # (0.04 <= 0.10), so BH rejects all five (audit 6.8 — the old code stopped at rank 1).
    p_vals = [0.04, 0.04, 0.04, 0.04, 0.04]
    flags = benjamini_hochberg(p_vals, fdr=0.10)
    assert all(f is True for f in flags)


def test_bh_rejects_clearly_significant():
    p_vals = [0.001, 0.002, 0.5, 0.8, 0.9]
    flags = benjamini_hochberg(p_vals, fdr=0.10)
    # First two should be rejected
    assert flags[0] is True
    assert flags[1] is True


def test_bh_empty():
    assert benjamini_hochberg([]) == []


def test_minimum_n_suppresses():
    assert check_minimum_n(50, min_n=100) is False


def test_minimum_n_passes():
    assert check_minimum_n(100) is True


def test_run_diagnostics_empty_no_eligible():
    result = run_diagnostics([
        {"symptom": "CTR_DROP", "observed": 0.01, "baseline": 0.05,
         "baseline_std": 0.01, "n": 10, "placement": "FEED", "device": "MOBILE"}
    ])
    assert result["symptoms"] == []
    assert result["suppressed_count"] == 1


def test_run_diagnostics_no_input():
    result = run_diagnostics([])
    assert result["symptoms"] == []
    assert result["suppressed_count"] == 0


def test_run_diagnostics_significant_symptom():
    # Clearly significant: observed = 0.001 vs baseline = 0.05 with tight std, n=500
    result = run_diagnostics([{
        "symptom": "CTR_DROP", "observed": 0.001, "baseline": 0.05,
        "baseline_std": 0.005, "n": 500, "placement": "FEED", "device": "MOBILE",
    }])
    assert len(result["symptoms"]) == 1
    assert result["symptoms"][0]["symptom"] == "CTR_DROP"
    assert result["bh_applied"] is True


def test_run_diagnostics_not_significant():
    # Barely different from baseline
    result = run_diagnostics([{
        "symptom": "CTR_DROP", "observed": 0.049, "baseline": 0.050,
        "baseline_std": 0.05, "n": 200, "placement": "FEED", "device": "MOBILE",
    }])
    assert len(result["symptoms"]) == 0


def test_lpv_click_gap_detected():
    r = compute_lpv_click_gap(page_views=30, meta_clicks=100)
    assert r["gap_detected"] is True
    assert r["ratio"] == pytest.approx(0.3, abs=0.01)


def test_lpv_click_gap_ok():
    r = compute_lpv_click_gap(page_views=80, meta_clicks=100)
    assert r["gap_detected"] is False


def test_lpv_click_gap_no_clicks():
    r = compute_lpv_click_gap(page_views=100, meta_clicks=0)
    assert r["gap_detected"] is False
    assert r["ratio"] is None
