"""Tests for form_qualification_service.py (vision §19.5)."""
import pytest
from app.services.form_qualification_service import (
    classify_ticket_bucket,
    validate_form_payload,
    compute_lead_score,
    calibrate_value_coefficients,
)


def test_validate_form_valid():
    result = validate_form_payload({
        "email": "investor@example.com",
        "first_name": "Jane",
        "last_name": "Smith",
        "self_accredited": True,
        "ticket_bucket": "50_100K",
    })
    assert result["valid"] is True
    assert result["errors"] == []
    assert result["normalized"]["email"] == "investor@example.com"
    assert result["normalized"]["self_accredited"] is True


def test_validate_form_no_email():
    result = validate_form_payload({"first_name": "Jane"})
    assert result["valid"] is False
    assert any("email" in e for e in result["errors"])


def test_validate_form_bad_email():
    result = validate_form_payload({"email": "notanemail", "first_name": "Jane"})
    assert result["valid"] is False
    assert any("format" in e for e in result["errors"])


def test_validate_form_no_first_name():
    result = validate_form_payload({"email": "x@x.com"})
    assert result["valid"] is False
    assert any("first_name" in e for e in result["errors"])


def test_validate_form_bad_ticket_bucket():
    result = validate_form_payload({
        "email": "x@x.com", "first_name": "A",
        "ticket_bucket": "INVALID_BUCKET",
    })
    assert result["valid"] is False
    assert any("ticket_bucket" in e for e in result["errors"])


def test_classify_ticket_bucket():
    assert classify_ticket_bucket(75_000) == "50_100K"


def test_classify_ticket_bucket_lt():
    assert classify_ticket_bucket(10_000) == "LT25K"


def test_classify_ticket_bucket_gt():
    assert classify_ticket_bucket(500_000) == "GT250K"


def test_classify_ticket_bucket_boundary():
    assert classify_ticket_bucket(25_000) == "25_50K"
    assert classify_ticket_bucket(24_999) == "LT25K"


def test_classify_ticket_bucket_none():
    assert classify_ticket_bucket(None) is None


def test_compute_lead_score_high():
    result = compute_lead_score(True, "GT250K")
    assert result["score"] == 1.0
    assert result["tier"] == "HIGH"


def test_compute_lead_score_low():
    result = compute_lead_score(False, "LT25K")
    assert result["score"] == 0.0
    assert result["tier"] == "LOW"


def test_compute_lead_score_mid():
    # accredited=True, 50_100K: k_accr=1.0, k_tick=1.0, raw=1.0, score=1/3≈0.333 → MED
    result = compute_lead_score(True, "50_100K")
    assert result["tier"] == "MED"


def test_compute_lead_score_none_accredited():
    result = compute_lead_score(None, "50_100K")
    assert result["k_accr"] == 0.3


def test_calibrate_value_insufficient():
    log = [{"predicted_value": 4000, "actual_value": 5000, "ticket_bucket": "50_100K"}] * 5
    result = calibrate_value_coefficients(log, min_samples=20)
    suggestion = result["suggestions"].get("50_100K")
    assert suggestion is not None
    assert suggestion["suggested"] is None
    assert "Insufficient" in suggestion["reason"]


def test_calibrate_value_sufficient():
    entry = {"predicted_value": 4000.0, "actual_value": 4800.0, "ticket_bucket": "50_100K"}
    log = [entry] * 25
    result = calibrate_value_coefficients(log, min_samples=20)
    suggestion = result["suggestions"]["50_100K"]
    assert suggestion["suggested"] is not None
    # ratio = 4800/4000 = 1.2; current = 1.0; suggested = 1.2
    assert abs(suggestion["suggested"] - 1.2) < 0.05
    assert suggestion["n"] == 25
