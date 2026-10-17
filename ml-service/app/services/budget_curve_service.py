"""Predictive Budget Curve Service (Phase 6-3, §8.4).

Monte Carlo simulation over Beta(alpha, beta) CVR distribution to produce
CPA corridors at each budget level.
"""
from typing import List, Optional
import numpy as np


def simulate_budget_step(
    alpha: float,
    beta: float,
    sessions_per_dollar: float,
    budget: float,
    n_mc: int = 2000,
    rng_seed: Optional[int] = None,
) -> dict:
    """Sample CVR from Beta(alpha, beta) n_mc times, compute CPA distribution.

    Returns p10, p50, p90 of CPA at the given budget.
    sessions_per_dollar: expected traffic volume per $ spent.
    """
    rng = np.random.default_rng(rng_seed)
    if alpha <= 0 or beta <= 0:
        alpha, beta = 1.0, 1.0

    cvr_samples    = rng.beta(alpha, beta, size=n_mc)
    sessions       = budget * sessions_per_dollar
    conversions    = cvr_samples * sessions

    with np.errstate(divide="ignore", invalid="ignore"):
        cpa_samples = np.where(conversions > 0, budget / conversions, np.inf)

    finite_mask = np.isfinite(cpa_samples)
    if finite_mask.sum() == 0:
        return {"budget": budget, "cpa_p10": None, "cpa_p50": None, "cpa_p90": None}

    cpa_finite = cpa_samples[finite_mask]
    return {
        "budget":  round(float(budget), 2),
        "cpa_p10": round(float(np.percentile(cpa_finite, 10)), 4),
        "cpa_p50": round(float(np.percentile(cpa_finite, 50)), 4),
        "cpa_p90": round(float(np.percentile(cpa_finite, 90)), 4),
    }


def compute_budget_curve(
    alpha: float,
    beta: float,
    sessions_per_dollar: float,
    budget_points: List[float],
    n_mc: int = 2000,
) -> List[dict]:
    """Compute budget curve across multiple budget points."""
    return [
        simulate_budget_step(alpha, beta, sessions_per_dollar, b, n_mc, rng_seed=i)
        for i, b in enumerate(budget_points)
    ]


def find_diminishing_returns_point(curve: List[dict]) -> Optional[float]:
    """Return the first budget where CPA p50 increases >20% vs the last valid step.

    Skips None entries rather than resetting the comparison baseline.
    Returns None if no such point found (curve is still efficient).
    """
    prev_cpa = None
    for step in curve:
        curr = step.get("cpa_p50")
        if curr is None:
            continue
        if prev_cpa is not None and prev_cpa > 0 and (curr - prev_cpa) / prev_cpa > 0.20:
            return step["budget"]
        prev_cpa = curr
    return None
