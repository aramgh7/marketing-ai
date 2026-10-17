"""
CPM Cost Model service — no FastAPI/Pydantic imports.
Bayesian regression: log(CPM) = f(atoms, placement, calendar, cpm_index).
LightGBM second opinion; divergence >30% → review flag.
Architecture §15.17, roadmap 4-6.
"""
from __future__ import annotations

import math
import numpy as np

# ── Placement and calendar priors (log-CPM additive effects) ──────────────────

PLACEMENT_EFFECTS: dict[str, float] = {
    "FEED": 0.0,
    "STORIES": -0.05,
    "REELS": -0.08,
    "AUDIENCE_NETWORK": -0.15,
    "MARKETPLACE": 0.05,
    "SEARCH": 0.10,
    "VIDEO_FEEDS": 0.03,
    "INSTAGRAM_EXPLORE": 0.02,
}

CALENDAR_EFFECTS: dict[str, float] = {
    "Q4": 0.35,
    "Q4_PEAK": 0.50,
    "HOLIDAY": 0.25,
    "WEEKEND": 0.05,
    "BLACK_FRIDAY": 0.60,
    "CYBER_MONDAY": 0.55,
    "Q1_SLUMP": -0.10,
}

# Baseline: ~ln(24) ≈ 3.178 → $24 CPM baseline
_LOG_CPM_BASE = 3.178
# Bayesian prior noise σ (log-CPM)
_POSTERIOR_SIGMA = 0.05

_RNG = np.random.default_rng(seed=42)


def _extract_features(
    atoms: list[str],
    placement: str,
    calendar_tags: list[str],
    cpm_index: float,
) -> dict:
    atom_count = len(atoms)
    placement_effect = PLACEMENT_EFFECTS.get(
        (placement or "FEED").upper(), 0.0
    )
    calendar_effect = max(
        (CALENDAR_EFFECTS.get(tag.upper(), 0.0) for tag in (calendar_tags or [])),
        default=0.0,
    )
    return {
        "atom_count": atom_count,
        "placement_effect": placement_effect,
        "calendar_effect": calendar_effect,
        "cpm_index": float(cpm_index or 0.0),
    }


def predict_cpm(
    atoms: list[str],
    placement: str,
    calendar_tags: list[str],
    cpm_index: float,
    n_samples: int = 1000,
) -> dict:
    """
    Bayesian regression estimate for CPM.
    log(CPM) = β0 + β1*atom_count + β2*placement + β3*calendar + β4*cpm_index
    Returns {cpm, ci_lower, ci_upper, lgb_cpm, divergence_pct, flag_review}
    """
    feats = _extract_features(atoms, placement, calendar_tags, cpm_index)

    noise = _RNG.normal(0, _POSTERIOR_SIGMA, n_samples)
    log_cpm_samples = (
        _LOG_CPM_BASE
        + 0.02 * feats["atom_count"]
        + feats["placement_effect"]
        + feats["calendar_effect"]
        + 0.30 * feats["cpm_index"]
        + noise
    )

    cpm_samples = np.exp(log_cpm_samples)
    cpm_mean = float(np.mean(cpm_samples))
    ci_lower = float(np.percentile(cpm_samples, 2.5))
    ci_upper = float(np.percentile(cpm_samples, 97.5))

    lgb_cpm = _lgb_cpm_opinion(feats)
    divergence = check_lgb_divergence(cpm_mean, lgb_cpm)

    return {
        "cpm": round(cpm_mean, 4),
        "ci_lower": round(ci_lower, 4),
        "ci_upper": round(ci_upper, 4),
        "lgb_cpm": round(lgb_cpm, 4),
        "divergence_pct": divergence["divergence_pct"],
        "flag_review": divergence["flag_review"],
    }


def _lgb_cpm_opinion(feats: dict) -> float:
    """
    LightGBM second opinion on CPM.
    In production: load a pre-fitted LGB model and call model.predict([feature_vector]).
    MVP stub: log-linear formula with slightly different coefficients to model real divergence.
    """
    log_cpm = (
        _LOG_CPM_BASE
        + 0.018 * feats["atom_count"]
        + feats["placement_effect"] * 1.1
        + feats["calendar_effect"] * 0.95
        + 0.28 * feats["cpm_index"]
    )
    return float(math.exp(log_cpm))


def check_lgb_divergence(bayes_cpm: float, lgb_cpm: float) -> dict:
    """
    Returns {divergence_pct, flag_review}.
    divergence_pct = |bayes_cpm - lgb_cpm| / bayes_cpm
    Disagreement >30% is flagged for human review (not auto-averaged — vision §15.11).
    """
    if bayes_cpm <= 0:
        return {"divergence_pct": 0.0, "flag_review": False}
    divergence_pct = abs(bayes_cpm - lgb_cpm) / bayes_cpm
    return {
        "divergence_pct": round(divergence_pct, 4),
        "flag_review": divergence_pct > 0.30,
    }
