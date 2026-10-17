"""Tests for model_quality_service — Block F §8."""
import pytest
import math
from app.services.model_quality_service import (
    compute_log_loss,
    compute_brier_score,
    compute_calibration_error,
    compute_rmse,
    evaluate_thompson_quality,
    assess_model_health,
    MIN_SAMPLE_FOR_QUALITY,
    LOG_LOSS_GOOD, LOG_LOSS_WARN,
    BRIER_GOOD, BRIER_WARN,
)


# ── log-loss ─────────────────────────────────────────────────────────────────

def test_log_loss_perfect():
    y_true = [1, 0, 1, 0]
    y_prob = [0.9999, 0.0001, 0.9999, 0.0001]
    ll = compute_log_loss(y_true, y_prob)
    assert ll < 0.01


def test_log_loss_random():
    y_true = [1, 0, 1, 0]
    y_prob = [0.5, 0.5, 0.5, 0.5]
    ll = compute_log_loss(y_true, y_prob)
    assert abs(ll - math.log(2)) < 0.001   # ≈ 0.693


def test_log_loss_empty():
    assert math.isinf(compute_log_loss([], []))


# ── Brier score ──────────────────────────────────────────────────────────────

def test_brier_perfect():
    y_true = [1, 1, 0, 0]
    y_prob = [1.0, 1.0, 0.0, 0.0]
    bs = compute_brier_score(y_true, y_prob)
    assert bs < 1e-6


def test_brier_worst():
    y_true = [1, 1, 0, 0]
    y_prob = [0.0, 0.0, 1.0, 1.0]
    bs = compute_brier_score(y_true, y_prob)
    assert abs(bs - 1.0) < 1e-6


# ── calibration error ────────────────────────────────────────────────────────

def test_calibration_perfect():
    # Well-calibrated: 50% predicted → 50% actual
    y_true = [1, 0] * 50
    y_prob = [0.5] * 100
    ece = compute_calibration_error(y_true, y_prob)
    assert ece < 0.05


def test_calibration_empty():
    assert math.isinf(compute_calibration_error([], []))


# ── RMSE ─────────────────────────────────────────────────────────────────────

def test_rmse_perfect():
    y = [1.0, 2.0, 3.0]
    assert compute_rmse(y, y) == 0.0


def test_rmse_basic():
    y_true = [1.0, 2.0, 3.0]
    y_pred = [2.0, 2.0, 2.0]
    # errors: 1, 0, 1 → mse=2/3 → rmse=sqrt(2/3)
    expected = math.sqrt(2 / 3)
    assert abs(compute_rmse(y_true, y_pred) - expected) < 0.001


def test_rmse_empty():
    assert math.isinf(compute_rmse([], []))


# ── evaluate_thompson_quality ────────────────────────────────────────────────

def test_thompson_insufficient_data():
    result = evaluate_thompson_quality([1, 0], [0.9, 0.1])
    assert result["status"] == "INSUFFICIENT_DATA"


def test_thompson_good_status():
    n = MIN_SAMPLE_FOR_QUALITY + 10
    # High-confidence correct predictions → GOOD
    y_true = ([1] * (n // 2)) + ([0] * (n // 2))
    y_prob = ([0.95] * (n // 2)) + ([0.05] * (n // 2))
    result = evaluate_thompson_quality(y_true, y_prob)
    assert result["status"] == "GOOD"
    assert "log_loss" in result
    assert "brier_score" in result


def test_thompson_degraded_status():
    n = MIN_SAMPLE_FOR_QUALITY + 10
    # Worst-case predictions → DEGRADED
    y_true = ([1] * (n // 2)) + ([0] * (n // 2))
    y_prob = ([0.01] * (n // 2)) + ([0.99] * (n // 2))
    result = evaluate_thompson_quality(y_true, y_prob)
    assert result["status"] == "DEGRADED"


# ── assess_model_health ───────────────────────────────────────────────────────

def test_assess_thompson_good():
    result = assess_model_health("thompson", {"log_loss": 0.2, "brier_score": 0.1, "calibration_error": 0.05})
    assert result["status"] == "GOOD"


def test_assess_thompson_degraded():
    result = assess_model_health("thompson", {"log_loss": 0.8, "brier_score": 0.3, "calibration_error": 0.2})
    assert result["status"] == "DEGRADED"


def test_assess_mmm_good():
    result = assess_model_health("mmm", {"rmse": 10.0, "mean_target": 100.0})
    assert result["status"] == "GOOD"   # 10% ratio < 20%


def test_assess_mmm_degraded():
    result = assess_model_health("mmm", {"rmse": 60.0, "mean_target": 100.0})
    assert result["status"] == "DEGRADED"   # 60% ratio > 50%


def test_assess_unknown_model():
    result = assess_model_health("nonexistent", {"foo": 1.0})
    assert result["status"] == "UNKNOWN"
