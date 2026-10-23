"""
Predicted Lead Value at Day 0 (vision §19.18.3; arch §16.16).
Pure logic — zero FastAPI/Pydantic imports.

Formula: p_show × p_verified|show × p_invest|verified × E[ticket|bucket]
Acceptance: calibration slope 0.8–1.2 on >=200 realised leads.
"""
from __future__ import annotations
import logging
from collections import defaultdict

_logger = logging.getLogger(__name__)

# ── Phase 12-4: PLV slope auto-recalibration state ───────────────────────────
# Counts consecutive out-of-range OLS slope results; resets on an in-range slope.
_ols_out_of_range_streak: int = 0
# Dynamically adjusted seed coefficient (soft correction when slope drifts).
_seed_p_invest_adjusted: float | None = None  # None = use SEED_P_INVEST_GIVEN_VERIFIED

E_TICKET = {
    "LT25K":    12_500,
    "25_50K":   37_500,
    "50_100K":  75_000,
    "100_250K": 175_000,
    "GT250K":   350_000,
}
DEFAULT_E_TICKET              = 75_000
SEED_P_INVEST_GIVEN_VERIFIED  = 0.21
SEED_P_VERIFIED_GIVEN_SHOW    = 0.65


def predict_lead_value(
    p_show:                  float,
    ticket_bucket:           str | None,
    p_verified_given_show:   float = SEED_P_VERIFIED_GIVEN_SHOW,
    p_invest_given_verified: float = SEED_P_INVEST_GIVEN_VERIFIED,
    e_ticket_override:       float | None = None,
    model_version:           str = "seed_v1",
) -> dict:
    """Day-0 predicted lead value."""
    e_ticket  = e_ticket_override or E_TICKET.get(ticket_bucket or "", DEFAULT_E_TICKET)
    predicted = p_show * p_verified_given_show * p_invest_given_verified * e_ticket

    return {
        "predicted_value": round(predicted, 2),
        "components": {
            "p_show":                  round(p_show, 4),
            "p_verified_given_show":   round(p_verified_given_show, 4),
            "p_invest_given_verified": round(p_invest_given_verified, 4),
            "e_ticket":                e_ticket,
            "ticket_bucket":           ticket_bucket,
        },
        "model_version":    model_version,
        "calibration_note": "Seed model — live when slope 0.8–1.2 on >=200 realised leads",
    }


def calibrate_value_model(
    predictions: list[dict],
    min_samples: int = 200,
) -> dict:
    """OLS calibration: predicted vs realised. Acceptance: slope ∈ [0.8, 1.2]."""
    valid = [
        p for p in predictions
        if p.get("predicted_value") is not None and p.get("realised_value") is not None
        and p["predicted_value"] > 0
    ]

    if len(valid) < min_samples:
        return {
            "calibrated": False,
            "n":          len(valid),
            "reason":     f"Need {min_samples} realised leads, have {len(valid)}",
            "slope":      None,
        }

    n      = len(valid)
    x_vals = [v["predicted_value"] for v in valid]
    y_vals = [v["realised_value"]   for v in valid]
    x_mean = sum(x_vals) / n
    y_mean = sum(y_vals) / n
    cov    = sum((x - x_mean) * (y - y_mean) for x, y in zip(x_vals, y_vals))
    var    = sum((x - x_mean) ** 2 for x in x_vals)
    slope  = cov / var if var > 0 else 0.0
    intercept = y_mean - slope * x_mean

    by_bucket: dict = defaultdict(lambda: {"n": 0, "sum_pred": 0.0, "sum_real": 0.0})
    for v in valid:
        bk = v.get("ticket_bucket", "unknown")
        by_bucket[bk]["n"]        += 1
        by_bucket[bk]["sum_pred"] += v["predicted_value"]
        by_bucket[bk]["sum_real"] += v["realised_value"]

    bucket_stats = {
        bk: {
            "n":         d["n"],
            "mean_pred": round(d["sum_pred"] / d["n"], 2),
            "mean_real": round(d["sum_real"] / d["n"], 2),
            "ratio":     round(d["sum_real"] / max(d["sum_pred"], 1), 3),
        }
        for bk, d in by_bucket.items()
    }

    calibrated = 0.8 <= slope <= 1.2

    # ── Phase 12-4: auto-recalibration ────────────────────────────────────────
    global _ols_out_of_range_streak, _seed_p_invest_adjusted

    if not calibrated:
        _ols_out_of_range_streak += 1
        _logger.warning(
            "PLV calibration slope=%.3f outside [0.8,1.2] — adjusting seed coefficients",
            slope,
        )
        base = SEED_P_INVEST_GIVEN_VERIFIED
        if slope > 1.2:
            _seed_p_invest_adjusted = base * min(slope / 1.0, 1.3)
        else:  # slope < 0.8
            _seed_p_invest_adjusted = base * max(slope / 1.0, 0.7)

        if _ols_out_of_range_streak >= 3:
            _logger.error(
                "ALERT PLV calibration slope out of range %d consecutive times",
                _ols_out_of_range_streak,
            )
    else:
        _ols_out_of_range_streak = 0

    return {
        "calibrated":              calibrated,
        "n":                       n,
        "slope":                   round(slope, 4),
        "intercept":               round(intercept, 2),
        "by_bucket":               bucket_stats,
        "acceptance":              "slope ∈ [0.8, 1.2]",
        "adjusted_p_invest":       round(_seed_p_invest_adjusted or SEED_P_INVEST_GIVEN_VERIFIED, 6),
        "ols_out_of_range_streak": _ols_out_of_range_streak,
    }
