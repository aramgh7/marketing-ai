"""
ML model quality metrics — log-loss, Brier score, calibration error (Block F §8).
Pure logic — ZERO FastAPI/Pydantic imports.
"""
from __future__ import annotations
import math
from typing import Optional

MIN_SAMPLE_FOR_QUALITY = 50  # minimum samples to compute reliable metrics

LOG_LOSS_GOOD      = 0.40   # below → good calibration
LOG_LOSS_WARN      = 0.65   # above → degrade warning
BRIER_GOOD         = 0.15
BRIER_WARN         = 0.25
CALIBRATION_WARN   = 0.10   # mean absolute calibration error


def _clip_prob(p: float, eps: float = 1e-7) -> float:
    return max(eps, min(1.0 - eps, p))


def compute_log_loss(y_true: list[int], y_prob: list[float]) -> float:
    """Binary log-loss."""
    if len(y_true) != len(y_prob) or not y_true:
        return float("inf")
    total = 0.0
    for y, p in zip(y_true, y_prob):
        p = _clip_prob(p)
        total += -(y * math.log(p) + (1 - y) * math.log(1 - p))
    return total / len(y_true)


def compute_brier_score(y_true: list[int], y_prob: list[float]) -> float:
    """Brier score (mean squared probability error)."""
    if len(y_true) != len(y_prob) or not y_true:
        return float("inf")
    return sum((p - y) ** 2 for y, p in zip(y_true, y_prob)) / len(y_true)


def compute_calibration_error(
    y_true: list[int],
    y_prob: list[float],
    n_bins: int = 10,
) -> float:
    """
    Expected Calibration Error (ECE): mean absolute difference between
    predicted probability and observed fraction in each bin.
    """
    if len(y_true) != len(y_prob) or not y_true:
        return float("inf")
    bins: dict[int, list] = {i: [] for i in range(n_bins)}
    for y, p in zip(y_true, y_prob):
        bin_idx = min(int(p * n_bins), n_bins - 1)
        bins[bin_idx].append((y, p))

    ece = 0.0
    n = len(y_true)
    for bin_data in bins.values():
        if not bin_data:
            continue
        ys = [d[0] for d in bin_data]
        ps = [d[1] for d in bin_data]
        fraction_positive = sum(ys) / len(ys)
        mean_pred = sum(ps) / len(ps)
        ece += (len(bin_data) / n) * abs(fraction_positive - mean_pred)
    return round(ece, 6)


def evaluate_thompson_quality(
    y_true: list[int],
    y_prob: list[float],
) -> dict:
    """
    Full quality report for a Thompson Sampling arm's predicted CVR.
    y_true: 1 = converted, 0 = not converted
    y_prob: predicted conversion probability from alpha/(alpha+beta)
    """
    n = len(y_true)
    if n < MIN_SAMPLE_FOR_QUALITY:
        return {
            "status":  "INSUFFICIENT_DATA",
            "n":       n,
            "min_n":   MIN_SAMPLE_FOR_QUALITY,
        }

    ll  = compute_log_loss(y_true, y_prob)
    bs  = compute_brier_score(y_true, y_prob)
    ece = compute_calibration_error(y_true, y_prob)

    if ll < LOG_LOSS_GOOD and bs < BRIER_GOOD and ece < CALIBRATION_WARN:
        status = "GOOD"
    elif ll > LOG_LOSS_WARN or bs > BRIER_WARN:
        status = "DEGRADED"
    else:
        status = "ACCEPTABLE"

    return {
        "status":            status,
        "n":                 n,
        "log_loss":          round(ll, 6),
        "brier_score":       round(bs, 6),
        "calibration_error": ece,
        "thresholds": {
            "log_loss_good":     LOG_LOSS_GOOD,
            "log_loss_warn":     LOG_LOSS_WARN,
            "brier_good":        BRIER_GOOD,
            "brier_warn":        BRIER_WARN,
            "calibration_warn":  CALIBRATION_WARN,
        },
    }


def compute_rmse(y_true: list[float], y_pred: list[float]) -> float:
    """Root Mean Squared Error — for regression models (MMM, GLM)."""
    if len(y_true) != len(y_pred) or not y_true:
        return float("inf")
    mse = sum((a - b) ** 2 for a, b in zip(y_true, y_pred)) / len(y_true)
    return round(math.sqrt(mse), 6)


def assess_model_health(
    model_name: str,
    metrics: dict[str, float],
) -> dict:
    """
    Generic health assessment — returns status GOOD|ACCEPTABLE|DEGRADED
    based on model-specific thresholds.
    """
    if model_name in ("thompson", "cluster"):
        ll  = metrics.get("log_loss", float("inf"))
        bs  = metrics.get("brier_score", float("inf"))
        ece = metrics.get("calibration_error", float("inf"))
        if ll < LOG_LOSS_GOOD and bs < BRIER_GOOD and ece < CALIBRATION_WARN:
            status = "GOOD"
        elif ll > LOG_LOSS_WARN or bs > BRIER_WARN:
            status = "DEGRADED"
        else:
            status = "ACCEPTABLE"
    elif model_name in ("mmm", "glm"):
        rmse = metrics.get("rmse", float("inf"))
        # Heuristic: RMSE > 50% of mean target is degraded
        mean_target = metrics.get("mean_target", 1.0)
        ratio = rmse / max(mean_target, 1e-9)
        if ratio < 0.20:
            status = "GOOD"
        elif ratio > 0.50:
            status = "DEGRADED"
        else:
            status = "ACCEPTABLE"
    else:
        status = "UNKNOWN"

    return {"model_name": model_name, "status": status, "metrics": metrics}
