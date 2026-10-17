"""
Tests for follow-up bandit service (vision §19.6 / arch §16.6).
Covers: context classification, Thompson selection, auto-pause, show-rate posterior.
"""
import pytest
from app.services.followup_service import (
    classify_context,
    thompson_select_variant,
    should_auto_pause,
    compute_show_rate_posterior,
)

_VARIANTS = [
    {"id": "v-ctrl", "name": "Control",     "alpha": 5.0,  "beta": 5.0,  "is_control": True,  "lifecycle_state": "APPROVED"},
    {"id": "v-a",    "name": "Variant A",   "alpha": 8.0,  "beta": 3.0,  "is_control": False, "lifecycle_state": "APPROVED"},
    {"id": "v-b",    "name": "Variant B",   "alpha": 2.0,  "beta": 8.0,  "is_control": False, "lifecycle_state": "APPROVED"},
]


def test_classify_context_domestic_low():
    assert classify_context("US", "LT25K") == "domestic:low_ticket"


def test_classify_context_intl_high():
    assert classify_context("CA", "GT250K") == "intl:high_ticket"


def test_classify_context_none_fallback():
    assert classify_context(None, None) == "domestic:low_ticket"


def test_thompson_returns_eligible_variant():
    result = thompson_select_variant(_VARIANTS, "domestic:high_ticket", exploration_rate=0.0)
    assert result["id"] in {"v-ctrl", "v-a", "v-b"}


def test_thompson_draft_excluded():
    variants = [
        {"id": "v-ctrl", "name": "Control", "alpha": 1.0, "beta": 1.0, "is_control": True,  "lifecycle_state": "APPROVED"},
        {"id": "v-draft","name": "Draft",   "alpha": 5.0, "beta": 1.0, "is_control": False, "lifecycle_state": "DRAFT"},
    ]
    # With exploration_rate=0, draft should never be chosen
    chosen_ids = {thompson_select_variant(variants, "x", exploration_rate=0.0)["id"] for _ in range(30)}
    assert "v-draft" not in chosen_ids


def test_auto_pause_low_ess_never_pauses():
    """Low ESS (<100) must NOT trigger auto-pause regardless of p_worse."""
    pause, p_worse = should_auto_pause(
        variant_alpha=1.5, variant_beta=10.0,
        control_alpha=5.0, control_beta=5.0,
        min_ess=100,
    )
    # ESS = 1.5 + 10.0 - 2 = 9.5 < 100
    assert pause is False


def test_auto_pause_clearly_worse():
    """Variant with alpha=1, beta=99 vs control alpha=50, beta=50 should be paused."""
    pause, p_worse = should_auto_pause(
        variant_alpha=1.0, variant_beta=99.0,
        control_alpha=50.0, control_beta=50.0,
        min_ess=50,
    )
    assert pause is True
    assert p_worse > 0.90


def test_show_rate_posterior_basic():
    result = compute_show_rate_posterior(showed=48, total=100)
    assert 0.4 < result["mean"] < 0.6
    assert result["ci_90_lo"] < result["mean"] < result["ci_90_hi"]
    assert result["ess"] == 100
