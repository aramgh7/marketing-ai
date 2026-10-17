"""Tests for burnout_survival_service.py — Block E (vision §15.8 task 2)."""
import math
import pytest
from app.services.burnout_survival_service import (
    N_MIN_FOR_SURVIVAL,
    encode_covariates,
    cox_hazard_ratio,
    weibull_survival,
    predict_burnout_days,
    should_suppress_burnout_alert,
)


# ── encode_covariates ─────────────────────────────────────────────────────────

def test_encode_covariates_basic():
    cov = encode_covariates(2.0, 3.0, "BROAD", "NORMAL")
    assert abs(cov["log_frequency"] - math.log(2.0)) < 1e-9
    assert abs(cov["log_initial_roas"] - math.log(3.0)) < 1e-9
    assert "audience_effect" in cov
    assert "season_effect" in cov


def test_encode_covariates_clamps_zero():
    cov = encode_covariates(0.0, 0.0)
    assert cov["log_frequency"] == math.log(0.01)
    assert cov["log_initial_roas"] == math.log(0.01)


def test_encode_covariates_unknown_audience():
    cov = encode_covariates(1.0, 1.0, audience_type="UNKNOWN_XYZ")
    assert cov["audience_effect"] == 0.0


# ── cox_hazard_ratio ──────────────────────────────────────────────────────────

def test_cox_hazard_ratio_positive():
    cov = encode_covariates(1.0, 1.0)
    hr = cox_hazard_ratio(cov)
    assert hr > 0


def test_cox_hazard_ratio_high_frequency_increases_hazard():
    cov_low  = encode_covariates(1.0, 2.0)
    cov_high = encode_covariates(10.0, 2.0)
    assert cox_hazard_ratio(cov_high) > cox_hazard_ratio(cov_low)


# ── weibull_survival ──────────────────────────────────────────────────────────

def test_weibull_survival_at_zero_is_one():
    assert weibull_survival(0) == 1.0


def test_weibull_survival_decreases_with_t():
    assert weibull_survival(5) > weibull_survival(10) > weibull_survival(20)


def test_weibull_survival_high_hr_burns_faster():
    assert weibull_survival(14, hazard_ratio=2.0) < weibull_survival(14, hazard_ratio=0.5)


# ── predict_burnout_days ──────────────────────────────────────────────────────

def test_predict_heuristic_below_n_min():
    result = predict_burnout_days(1.0, 2.0, 0, n_obs=N_MIN_FOR_SURVIVAL - 1)
    assert result["method"] == "HEURISTIC"
    assert result["confidence"] == "LOW"
    assert "n_obs=" in result["note"]


def test_predict_survival_above_n_min():
    result = predict_burnout_days(2.0, 3.0, 5, n_obs=N_MIN_FOR_SURVIVAL)
    assert result["method"] == "SURVIVAL"
    assert result["predicted_burnout_day"] > 5
    assert result["days_remaining"] >= 0


def test_predict_days_remaining_decreases_as_running_increases():
    r_early = predict_burnout_days(2.0, 2.0, 5,  n_obs=50)
    r_late  = predict_burnout_days(2.0, 2.0, 20, n_obs=50)
    assert r_early["days_remaining"] > r_late["days_remaining"]


# ── should_suppress_burnout_alert ────────────────────────────────────────────

def test_suppress_market_verdict():
    assert should_suppress_burnout_alert("MARKET") is True


def test_suppress_market_shift_verdict():
    assert should_suppress_burnout_alert("MARKET_SHIFT") is True


def test_no_suppress_none():
    assert should_suppress_burnout_alert(None) is False


def test_no_suppress_fraud():
    assert should_suppress_burnout_alert("FRAUD") is False
