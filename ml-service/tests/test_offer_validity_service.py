"""Tests for offer_validity_service — vision §4.3a offer mask."""
import pytest
from app.services.offer_validity_service import (
    run_offer_validity_checks,
    validate_discount,
    validate_guarantee,
    validate_urgency,
    validate_margin,
    check_installment_legal,
)


# ── validate_discount ────────────────────────────────────────────────────────

def test_discount_none_with_depth_fails():
    v = validate_discount("none", 10.0)
    assert v is not None
    assert "discount_depth" in v["field"]


def test_discount_none_with_zero_passes():
    assert validate_discount("none", 0.0) is None


def test_discount_pct_with_depth_passes():
    assert validate_discount("percentage", 15.0) is None


# ── validate_guarantee ───────────────────────────────────────────────────────

def test_guarantee_none_with_days_fails():
    v = validate_guarantee("none", 30)
    assert v is not None
    assert "guarantee_days" in v["field"]


def test_guarantee_none_null_days_passes():
    assert validate_guarantee("none", None) is None


def test_guarantee_money_back_with_days_passes():
    assert validate_guarantee("money_back_30", 30) is None


# ── validate_urgency ─────────────────────────────────────────────────────────

def test_urgency_real_no_source_fails():
    v = validate_urgency(True, None)
    assert v is not None
    assert "urgency_source" in v["field"]


def test_urgency_real_fake_timer_fails():
    v = validate_urgency(True, "fake_timer")
    assert v is not None
    assert "Fake timers" in v["reason"]


def test_urgency_real_valid_source_passes():
    assert validate_urgency(True, "inventory_api") is None
    assert validate_urgency(True, "crm_deadline") is None
    assert validate_urgency(True, "seasonal_window") is None


def test_urgency_false_no_source_passes():
    assert validate_urgency(False, None) is None


# ── validate_margin ──────────────────────────────────────────────────────────

def test_margin_below_floor_fails():
    v = validate_margin(0.10, 0.20)
    assert v is not None
    assert "estimated_margin" in v["field"]


def test_margin_above_floor_passes():
    assert validate_margin(0.25, 0.20) is None


def test_margin_exact_floor_passes():
    assert validate_margin(0.20, 0.20) is None


def test_margin_no_floor_passes():
    assert validate_margin(0.10, None) is None


def test_margin_no_estimated_passes():
    assert validate_margin(None, 0.20) is None


# ── check_installment_legal ──────────────────────────────────────────────────

def test_installment_requires_legal():
    assert check_installment_legal({"has_installment": True}) is True
    assert check_installment_legal({"payment_type": "installment"}) is True
    assert check_installment_legal({"payment_terms": "installment"}) is True


def test_no_installment_no_legal():
    assert check_installment_legal({"payment_type": "full"}) is False
    assert check_installment_legal({}) is False


# ── run_offer_validity_checks ────────────────────────────────────────────────

def test_run_all_checks_valid_offer():
    result = run_offer_validity_checks({
        "discount_type":   "percentage",
        "discount_depth":  0.15,
        "guarantee_type":  "money_back_30",
        "guarantee_days":  30,
        "has_real_urgency": True,
        "urgency_source":  "inventory_api",
        "estimated_margin": 0.30,
    }, min_margin=0.20)
    assert result["valid"] is True
    assert result["violations"] == []
    assert result["requires_legal_review"] is False


def test_run_all_checks_multiple_violations():
    result = run_offer_validity_checks({
        "discount_type":   "none",
        "discount_depth":  10.0,
        "guarantee_type":  "none",
        "guarantee_days":  30,
        "has_real_urgency": True,
        "urgency_source":  "fake_timer",
        "estimated_margin": 0.10,
    }, min_margin=0.25)
    assert result["valid"] is False
    assert len(result["violations"]) >= 3


def test_run_all_checks_installment_legal_flag():
    result = run_offer_validity_checks({
        "discount_type": "none",
        "discount_depth": 0,
        "guarantee_type": "none",
        "has_real_urgency": False,
        "payment_terms": "installment",
    })
    assert result["valid"] is True
    assert result["requires_legal_review"] is True


def test_run_all_checks_note_present():
    result = run_offer_validity_checks({})
    assert "landings only" in result["note"]
