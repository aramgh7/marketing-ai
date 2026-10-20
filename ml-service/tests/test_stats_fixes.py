"""Audit §6 statistical fixes (step 8) — pure functions, no I/O."""
import math

import pytest

from app.services.cb_bayesian_service import compute_cpa_exceedance_probability
from app.services.diagnostics_service import (
    RISE_IS_BAD,
    benjamini_hochberg,
    run_diagnostics,
    symptom_p_value,
)
from app.services.form_qualification_service import calibrate_value_coefficients
from app.services.lead_value_service import calibrate_value_model
from app.services.ope_service import compute_ope
from app.services.quality_service import detect_cvr_collapse


# ─── 6.1 / 6.2 OPE verdict is relative to the logged reward rate ───

def _decisions(n=400, rate=0.04):
    return [{"arm_id": "a", "logged_prob": 0.5, "reward": 1.0 if i % int(1 / rate) == 0 else 0.0}
            for i in range(n)]


def test_ope_identical_policy_passes_at_low_reward_rate():
    # SNIPS equals the logged mean reward (~0.04); the old rule `snips >= 0.95` always FAILED.
    res = compute_ope(_decisions(), {"a": 0.5}, w_max=10.0, min_ess=100.0)
    assert res["verdict"] == "PASS"
    assert res["logged_mean_reward"] == pytest.approx(res["snips"], rel=1e-6)
    assert res["ci_low"] <= res["snips"] <= res["ci_high"]


def test_ope_worse_policy_fails():
    # current policy puts almost no weight on the rewarded arm pattern: reward-bearing decisions
    # get a low weight, so SNIPS falls well below the logged rate.
    decs = _decisions()
    for i, d in enumerate(decs):
        d["arm_id"] = "good" if d["reward"] else "bad"
    res = compute_ope(decs, {"good": 0.05, "bad": 0.95}, w_max=10.0, min_ess=50.0)
    assert res["snips"] < 0.95 * res["logged_mean_reward"]
    assert res["verdict"] == "FAIL"


def test_ope_ci_brackets_snips_not_logged_rate():
    decs = _decisions()
    for d in decs:
        d["arm_id"] = "good" if d["reward"] else "bad"
    res = compute_ope(decs, {"good": 0.9, "bad": 0.1}, w_max=10.0, min_ess=10.0)
    assert res["ci_low"] <= res["snips"] <= res["ci_high"]
    assert res["snips"] > res["logged_mean_reward"]  # better policy -> higher estimated rate


# ─── 6.5 circuit breaker: zero conversions handled by the Beta model ───

def test_cb_many_cheap_clicks_zero_conversions_does_not_trigger():
    # 500 clicks, $99 spend, target $50 (threshold $100): the old branch fired at p = 1.0 because
    # spend > threshold/2. Beta(1,501) says P(CVR < 0.00198) ≈ 0.63 — uncertain, no trigger.
    res = compute_cpa_exceedance_probability(clicks=500, conversions=0, total_spend=99.0, target_cpa=50.0)
    assert res["observed_cpa"] is None
    assert 0.3 < res["p_exceed"] < 0.9
    assert res["should_trigger"] is False


def test_cb_single_cheap_click_is_not_evidence():
    res = compute_cpa_exceedance_probability(clicks=1, conversions=0, total_spend=1.0, target_cpa=50.0)
    assert res["p_exceed"] < 0.1
    assert res["should_trigger"] is False


def test_cb_many_clicks_zero_conversions_triggers():
    # 500 clicks, $99 spend, target $50: old branch was silent (99 < 100*0.5? no — 99 > 50 -> fired)
    # but 500 clicks / $1000 / target $100 is the canonical case: Beta(1,501) says CVR < 0.01 w.h.p.
    res = compute_cpa_exceedance_probability(clicks=500, conversions=0, total_spend=1000.0, target_cpa=100.0)
    assert res["should_trigger"] is True
    assert res["p_exceed"] > 0.9


# ─── 6.6 CVR collapse z-test ───

def test_cvr_collapse_full_collapse_no_zero_division():
    res = detect_cvr_collapse(current_cvr=0.0, baseline_cvr=0.05, n_conversions=0,
                              n_sessions=400, baseline_n=4000)
    assert res["collapsed"] is True
    assert res["p_value_approx"] < 0.05


def test_cvr_collapse_small_drop_not_significant():
    res = detect_cvr_collapse(current_cvr=0.048, baseline_cvr=0.05, n_conversions=19,
                              n_sessions=400, baseline_n=4000)
    assert res["collapsed"] is False


def test_cvr_collapse_defaults_baseline_n_to_current_window():
    a = detect_cvr_collapse(0.01, 0.05, 4, 400)
    b = detect_cvr_collapse(0.01, 0.05, 4, 400, baseline_n=400)
    assert a == b


# ─── 6.7 / 6.8 diagnostics ───

def test_rise_symptoms_use_upper_tail():
    assert "BOUNCE_SPIKE" in RISE_IS_BAD
    assert symptom_p_value("BOUNCE_SPIKE", 3.0) < 0.01      # bounce went UP -> significant
    assert symptom_p_value("BOUNCE_SPIKE", -3.0) > 0.99
    assert symptom_p_value("CTR_DROP", -3.0) < 0.01         # CTR went DOWN -> significant
    assert symptom_p_value("CTR_DROP", 3.0) > 0.99


def test_bounce_spike_detected_end_to_end():
    obs = [{"symptom": "BOUNCE_SPIKE", "observed": 0.80, "baseline": 0.50,
            "baseline_std": 0.10, "n": 400, "placement": "feed", "device": "mobile"}]
    out = run_diagnostics(obs, fdr=0.10)
    assert [s["symptom"] for s in out["symptoms"]] == ["BOUNCE_SPIKE"]


def test_bh_largest_k_rule():
    # audit 6.8 example: all three are rejected under BH at q = 0.10
    assert benjamini_hochberg([0.04, 0.05, 0.06], fdr=0.10) == [True, True, True]


def test_bh_mixed():
    flags = benjamini_hochberg([0.001, 0.30, 0.04, 0.90], fdr=0.10)
    assert flags == [True, False, True, False]


# ─── 6.9 calibration keeps realised = 0 ───

def test_lead_value_calibration_counts_zero_realised():
    preds = [{"predicted_value": 100.0, "realised_value": 0.0} for _ in range(150)] + \
            [{"predicted_value": 100.0, "realised_value": 100.0} for _ in range(60)]
    res = calibrate_value_model(preds, min_samples=200)
    assert res["n"] == 210


def test_form_calibration_counts_zero_actual():
    log = [{"predicted_value": 50.0, "actual_value": 0.0, "ticket_bucket": "lt_25k"} for _ in range(25)]
    res = calibrate_value_coefficients(log, min_samples=20)
    assert res["suggestions"]["lt_25k"]["n"] == 25


# ─── 6.10 GLM atom column names ───

def test_glm_atom_columns_renamed_and_face_binarised():
    pd = pytest.importorskip("pandas")
    from app.services.glm_features import normalise_atom_columns
    wide = pd.DataFrame({"creative_id": ["c1", "c2"], "HOOK": ["question", "shock"],
                         "CTA": ["book_call", None], "FACE": ["present", "none"]})
    out = normalise_atom_columns(wide)
    assert set(out.columns) >= {"hook_type", "cta_type", "has_face"}
    assert out["has_face"].tolist() == [1.0, 0.0]
    assert out["hook_type"].tolist() == ["question", "shock"]
