"""
Burnout survival model — pure logic, no FastAPI/Pydantic imports.
Cox PH simplified implementation. Heuristic kept as fallback below n_min.
vision §15.8 task 2: survival model on frequency, initial_roas, audience_type, season.
"""
from __future__ import annotations
import math
from typing import Optional

N_MIN_FOR_SURVIVAL = 30

DEFAULT_BETAS = {
    "log_frequency":     0.8,
    "log_initial_roas": -0.5,
    "audience_broad":   -0.2,
    "audience_retarget": 0.4,
    "season_peak":       0.3,
}

AUDIENCE_TYPE_ENCODING = {
    "BROAD":       "audience_broad",
    "LOOKALIKE":    0.0,
    "RETARGETING": "audience_retarget",
    "CUSTOM":       0.1,
}

SEASON_ENCODING = {
    "PEAK":   "season_peak",
    "NORMAL":  0.0,
    "LOW":    -0.2,
}


def encode_covariates(
    frequency: float,
    initial_roas: float,
    audience_type: str = "BROAD",
    season: str = "NORMAL",
) -> dict:
    cov = {
        "log_frequency":    math.log(max(frequency, 0.01)),
        "log_initial_roas": math.log(max(initial_roas, 0.01)),
    }
    aud_key = AUDIENCE_TYPE_ENCODING.get(audience_type.upper(), 0.0)
    cov["audience_effect"] = DEFAULT_BETAS.get(aud_key, 0.0) if isinstance(aud_key, str) else float(aud_key)
    sea_key = SEASON_ENCODING.get(season.upper(), 0.0)
    cov["season_effect"] = DEFAULT_BETAS.get(sea_key, 0.0) if isinstance(sea_key, str) else float(sea_key)
    return cov


def cox_hazard_ratio(covariates: dict) -> float:
    linear = (
        DEFAULT_BETAS["log_frequency"]    * covariates["log_frequency"]
      + DEFAULT_BETAS["log_initial_roas"] * covariates["log_initial_roas"]
      + covariates.get("audience_effect", 0.0)
      + covariates.get("season_effect", 0.0)
    )
    return math.exp(linear)


def weibull_survival(t: float, scale: float = 14.0, shape: float = 1.5, hazard_ratio: float = 1.0) -> float:
    """S(t) = exp(-(t/λ_eff)^k), λ_eff = scale / HR^(1/k)."""
    if t <= 0:
        return 1.0
    effective_scale = scale / (hazard_ratio ** (1.0 / shape))
    return math.exp(-((t / effective_scale) ** shape))


def predict_burnout_days(
    frequency: float,
    initial_roas: float,
    current_days_running: int,
    audience_type: str = "BROAD",
    season: str = "NORMAL",
    survival_threshold: float = 0.5,
    n_obs: int = 0,
) -> dict:
    """
    Predict days until burnout (S(t) < survival_threshold).
    Falls back to heuristic when n_obs < N_MIN_FOR_SURVIVAL.
    """
    if n_obs < N_MIN_FOR_SURVIVAL:
        heuristic_days = max(7, int(21 / max(frequency, 0.5)))
        return {
            "predicted_burnout_day": heuristic_days,
            "days_remaining":        max(0, heuristic_days - current_days_running),
            "survival_at_current":   None,
            "method":                "HEURISTIC",
            "confidence":            "LOW",
            "note":                  f"n_obs={n_obs} < {N_MIN_FOR_SURVIVAL} — using heuristic",
        }

    cov = encode_covariates(frequency, initial_roas, audience_type, season)
    hr  = cox_hazard_ratio(cov)
    s_current = weibull_survival(current_days_running, hazard_ratio=hr)

    # Binary search for day when S(t) < threshold
    lo, hi = float(current_days_running), float(current_days_running + 120)
    for _ in range(40):
        mid = (lo + hi) / 2
        if weibull_survival(mid, hazard_ratio=hr) < survival_threshold:
            hi = mid
        else:
            lo = mid
    burnout_day = int(hi)

    confidence = "HIGH" if n_obs >= 100 else ("MEDIUM" if n_obs >= 50 else "LOW")
    return {
        "predicted_burnout_day": burnout_day,
        "days_remaining":        max(0, burnout_day - current_days_running),
        "survival_at_current":   round(s_current, 4),
        "hazard_ratio":          round(hr, 4),
        "method":                "SURVIVAL",
        "confidence":            confidence,
        "n_obs":                 n_obs,
    }


def should_suppress_burnout_alert(anchor_verdict: Optional[str]) -> bool:
    """Suppress burnout alert when anchor verdict is MARKET — vision §8.1."""
    return anchor_verdict in ("MARKET", "MARKET_SHIFT")
