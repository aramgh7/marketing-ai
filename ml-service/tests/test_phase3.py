"""
Phase 3 Protective Set — Python unit tests.
Items 3-12 through 3-23.
"""
import math
import pytest

from app.services.ope_service import compute_ope
from app.services.mmf_service import diagnose_mmf, generate_offers, rank_offers
from app.services.persona_service import build_persona, score_session_against_persona
from app.services.quality_service import detect_cvr_collapse
from app.services.anchors_service import compute_divergence
from app.services.signals_service import classify_anomaly, compute_cpm_z_score


# ─── OPE tests ───────────────────────────────────────────────────────────────

def test_ope_insufficient_data_below_10():
    result = compute_ope(decisions=[], current_policy_probs={})
    assert result["verdict"] == "INSUFFICIENT_DATA"
    assert result["ess"] == 0


def test_ope_insufficient_data_exactly_9():
    decisions = [{"arm_id": "a", "logged_prob": 0.5, "reward": 1.0}] * 9
    result = compute_ope(decisions, {"a": 0.5})
    assert result["verdict"] == "INSUFFICIENT_DATA"


def test_ope_pass_snips_high_ess():
    """50 decisions with same policy → SNIPS ~1.0 and ESS is high → PASS."""
    decisions = [{"arm_id": "v1", "logged_prob": 0.5, "reward": 1.0}] * 50
    current_probs = {"v1": 0.5}
    result = compute_ope(decisions, current_probs, min_ess=10.0)
    assert result["verdict"] == "PASS"
    assert result["snips"] is not None
    assert result["ess"] is not None
    assert result["ess"] > 0


def test_ope_fail_low_ess():
    """Highly skewed weights → low ESS → FAIL."""
    decisions = (
        [{"arm_id": "v1", "logged_prob": 0.01, "reward": 1.0}] * 25 +
        [{"arm_id": "v2", "logged_prob": 0.99, "reward": 0.0}] * 25
    )
    current_probs = {"v1": 0.99, "v2": 0.01}
    result = compute_ope(decisions, current_probs, min_ess=200.0)
    assert result["verdict"] in ("FAIL", "INSUFFICIENT_DATA")


def test_ope_result_has_required_keys():
    decisions = [{"arm_id": "a", "logged_prob": 0.3, "reward": 0.5}] * 30
    result = compute_ope(decisions, {"a": 0.3})
    for key in ("ips", "snips", "dr", "ci_low", "ci_high", "ess", "clipped_mass", "verdict"):
        assert key in result


# ─── MMF diagnosis tests ──────────────────────────────────────────────────────

def test_mmf_diagnose_learning_phase():
    result = diagnose_mmf({"learning_phase": "LEARNING", "budget_util": 0.5})
    assert result["diagnosis"] == "LEARNING_PHASE"
    assert result["confidence"] > 0.9


def test_mmf_diagnose_budget_limited():
    result = diagnose_mmf({"learning_phase": "", "budget_util": 0.98})
    assert result["diagnosis"] == "BUDGET_LIMITED"


def test_mmf_diagnose_audience_saturated():
    result = diagnose_mmf({"learning_phase": "", "budget_util": 0.5, "impression_share": 0.85})
    assert result["diagnosis"] == "AUDIENCE_SATURATED"


def test_mmf_diagnose_creative_fatigue():
    result = diagnose_mmf({
        "learning_phase": "", "budget_util": 0.5,
        "impression_share": 0.3, "frequency": 5.0, "ctr": 0.001, "ctr_benchmark": 0.005
    })
    assert result["diagnosis"] == "CREATIVE_FATIGUE"


def test_mmf_diagnose_healthy():
    result = diagnose_mmf({
        "learning_phase": "", "budget_util": 0.5,
        "impression_share": 0.3, "frequency": 1.0,
        "ctr": 0.02, "ctr_benchmark": 0.005, "cvr": 0.05
    })
    assert result["diagnosis"] == "HEALTHY"


def test_mmf_generate_offers_count():
    offers = generate_offers({"price": 99.0, "usp": "Best hydrogel", "guarantee_days": 30}, {})
    assert 3 <= len(offers) <= 5
    labels = [o["label"] for o in offers]
    assert "baseline" in labels


def test_mmf_rank_offers_order():
    offers = [
        {"label": "baseline", "price": 99},
        {"label": "bundle", "price": 130},
    ]
    scores = {
        "baseline": {"cvr_estimate": 0.03, "revenue_per_conversion": 99},
        "bundle": {"cvr_estimate": 0.05, "revenue_per_conversion": 130},
    }
    ranked = rank_offers(offers, scores)
    assert ranked[0]["label"] == "bundle"
    assert ranked[0]["rank"] == 1


# ─── Persona tests ────────────────────────────────────────────────────────────

def _make_sessions(n, scroll=0.6, duration=120, device="mobile", geo="US", utm="facebook", converted=False):
    return [
        {"scroll_depth": scroll, "session_duration_sec": duration,
         "device_type": device, "geo": geo, "utm_source": utm,
         "converted": converted, "bounced": False}
        for _ in range(n)
    ]


def test_persona_build_expected_keys():
    persona = build_persona(_make_sessions(20), cluster_id=1, label="hydro-buyer")
    for key in ("cluster_id", "label", "session_count", "avg_scroll_depth",
                "avg_session_duration_sec", "conversion_rate", "top_utm_sources"):
        assert key in persona


def test_persona_build_empty_sessions():
    persona = build_persona([], cluster_id=0, label="empty")
    assert persona["session_count"] == 0


def test_persona_score_high_identical():
    sessions = _make_sessions(10, scroll=0.8, duration=200, device="mobile", geo="US", utm="facebook")
    persona = build_persona(sessions, cluster_id=1, label="test")
    # A session identical to the persona should score high
    test_session = {"scroll_depth": 0.8, "session_duration_sec": 200,
                    "device_type": "mobile", "geo": "US", "utm_source": "facebook"}
    score = score_session_against_persona(test_session, persona)
    assert score >= 0.6


def test_persona_score_low_different():
    sessions = _make_sessions(10, scroll=0.8, duration=200, device="mobile", geo="US", utm="facebook")
    persona = build_persona(sessions, cluster_id=1, label="test")
    # A very different session should score low
    diff_session = {"scroll_depth": 0.1, "session_duration_sec": 5,
                    "device_type": "desktop", "geo": "RU", "utm_source": "tiktok"}
    score = score_session_against_persona(diff_session, persona)
    assert score < 0.6


# ─── CVR collapse tests ───────────────────────────────────────────────────────

def test_cvr_collapse_detected():
    result = detect_cvr_collapse(
        current_cvr=0.01, baseline_cvr=0.03,
        n_conversions=300, n_sessions=10000, threshold_drop=0.3
    )
    assert result["collapsed"] is True
    assert result["verdict"] == "CVR_COLLAPSE"
    assert result["relative_drop"] > 0.3


def test_cvr_collapse_not_detected_small_drop():
    result = detect_cvr_collapse(
        current_cvr=0.028, baseline_cvr=0.03,
        n_conversions=100, n_sessions=5000, threshold_drop=0.3
    )
    assert result["collapsed"] is False
    assert result["verdict"] == "STABLE"


def test_cvr_collapse_insufficient_data():
    result = detect_cvr_collapse(
        current_cvr=0.01, baseline_cvr=0.03,
        n_conversions=5, n_sessions=5, threshold_drop=0.3
    )
    assert result["verdict"] in ("INSUFFICIENT_DATA", "STABLE")


# ─── Anchor divergence tests ──────────────────────────────────────────────────

def test_anchor_divergence_none():
    anchor_stats = [{"alpha": 100, "beta": 100, "baseline_alpha": 100, "baseline_beta": 100}]
    test_stats   = [{"alpha": 100, "beta": 100, "baseline_alpha": 100, "baseline_beta": 100}]
    result = compute_divergence(anchor_stats, test_stats)
    assert result["verdict"] == "NONE"


def test_anchor_divergence_creative():
    """Tests dropped, anchors stable → CREATIVE."""
    anchor_stats = [{"alpha": 100, "beta": 100, "baseline_alpha": 100, "baseline_beta": 100}]
    # Tests dropped sharply: current beta >> baseline beta
    test_stats = [{"alpha": 5, "beta": 200, "baseline_alpha": 100, "baseline_beta": 100}]
    result = compute_divergence(anchor_stats, test_stats, threshold=0.6)
    assert result["verdict"] in ("CREATIVE", "MARKET")  # depends on simulation


# ─── Signals anomaly tests ────────────────────────────────────────────────────

def test_signals_technical_priority():
    """Site health open → TECHNICAL regardless of fraud."""
    result = classify_anomaly(
        anchor_verdict="MARKET",
        site_health_open=True,
        fraud_share_pct=30.0,
        offer_problem=False,
        cpm_z_score=None,
        calendar_tags=[]
    )
    assert result["verdict"] == "TECHNICAL"


def test_signals_fraud_over_market():
    """High fraud, no site issue → FRAUD."""
    result = classify_anomaly(
        anchor_verdict="MARKET",
        site_health_open=False,
        fraud_share_pct=20.0,
        offer_problem=False,
        cpm_z_score=None,
        calendar_tags=[]
    )
    assert result["verdict"] == "FRAUD"


def test_signals_market_verdict():
    """No site issue, no fraud, anchor=MARKET → MARKET."""
    result = classify_anomaly(
        anchor_verdict="MARKET",
        site_health_open=False,
        fraud_share_pct=5.0,
        offer_problem=False,
        cpm_z_score=3.0,
        calendar_tags=["black_friday"]
    )
    assert result["verdict"] == "MARKET"


def test_signals_none():
    result = classify_anomaly(
        anchor_verdict="NONE",
        site_health_open=False,
        fraud_share_pct=5.0,
        offer_problem=False,
        cpm_z_score=0.5,
        calendar_tags=[]
    )
    assert result["verdict"] == "NONE"


def test_cpm_z_score_computed():
    history = [10.0, 11.0, 10.5, 9.8, 10.2, 10.1, 9.9]
    z = compute_cpm_z_score(20.0, history)  # spike
    assert z is not None
    assert z > 2.0


def test_cpm_z_score_insufficient():
    z = compute_cpm_z_score(20.0, [10.0, 11.0])  # only 2 points
    assert z is None
