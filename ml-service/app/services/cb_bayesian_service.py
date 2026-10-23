"""
Circuit Breaker Bayesian L1 trigger — pure logic, no FastAPI/Pydantic imports.
P(CPA > k * target_cpa | data) via Monte Carlo Beta posterior on CVR.
vision §12.5a L1 row: P(CPA > 2× target) > 0.90 triggers pause.
"""
from __future__ import annotations
import math
from typing import Optional


def compute_cpa_exceedance_probability(
    clicks: int,
    conversions: int,
    total_spend: float,
    target_cpa: float,
    cpa_multiplier: float = 2.0,
    n_samples: int = 2_000,
) -> dict:
    """
    P(CPA > cpa_multiplier * target_cpa | observed data).

    Model: CVR ~ Beta(α=conversions+1, β=clicks-conversions+1)
           CPA  = spend_per_click / CVR
    Returns p_exceed, should_trigger (p > 0.90), observed_cpa.
    """
    threshold = target_cpa * cpa_multiplier

    if clicks < 1 or total_spend <= 0:
        return {
            "p_exceed": 0.0,
            "should_trigger": False,
            "observed_cpa": None,
            "reason": "Insufficient data (no clicks or spend)",
        }

    # Audit 6.5: no special case for zero conversions — Beta(1, clicks+1) already encodes it.
    # (The old branch fired on 1 click / $101 and stayed silent on 500 clicks / $99.)
    observed_cpa = total_spend / conversions if conversions > 0 else None

    alpha_cvr = float(conversions) + 1.0
    beta_cvr  = float(clicks - conversions) + 1.0

    try:
        import random
        rng = random.Random()  # unseeded: deterministic seed hid MC variance
        spend_per_click = total_spend / clicks
        exceed_count = 0
        for _ in range(n_samples):
            cvr_s = rng.betavariate(alpha_cvr, beta_cvr)
            if cvr_s < 1e-9:
                exceed_count += 1
                continue
            cpa_s = spend_per_click / cvr_s
            if cpa_s > threshold:
                exceed_count += 1
        p_exceed = exceed_count / n_samples

    except Exception:
        p_exceed = 1.0 if (observed_cpa is None or observed_cpa > threshold) else 0.0

    return {
        "p_exceed": round(p_exceed, 4),
        "should_trigger": p_exceed > 0.90,
        "observed_cpa": round(observed_cpa, 2) if observed_cpa is not None else None,
        "threshold_cpa": round(threshold, 2),
        "target_cpa": target_cpa,
        "cpa_multiplier": cpa_multiplier,
        "clicks": clicks,
        "conversions": conversions,
        "reason": (
            f"P(CPA > {threshold:.2f}) = {p_exceed:.3f} "
            f"{'→ TRIGGER' if p_exceed > 0.90 else '→ OK'}"
        ),
    }


def l1_combined_check(
    spend: float,
    spend_limit: float,
    clicks: int,
    conversions: int,
    target_cpa: float,
    cpa_multiplier: float = 2.0,
) -> dict:
    """
    Combined L1: spend-based OR Bayesian CPA-based trigger.
    Either condition fires the circuit breaker.
    """
    spend_trigger = spend >= spend_limit
    bayesian = compute_cpa_exceedance_probability(
        clicks, conversions, spend, target_cpa, cpa_multiplier
    )
    return {
        "spend_trigger":    spend_trigger,
        "bayesian_trigger": bayesian["should_trigger"],
        "should_pause":     spend_trigger or bayesian["should_trigger"],
        "spend":            round(spend, 2),
        "spend_limit":      spend_limit,
        "p_cpa_exceed":     bayesian["p_exceed"],
        "observed_cpa":     bayesian.get("observed_cpa"),
        "threshold_cpa":    bayesian.get("threshold_cpa"),
        "reason": (
            f"Spend {'≥' if spend_trigger else '<'} limit={spend_limit:.2f}; "
            f"P(CPA>2×target)={bayesian['p_exceed']:.3f}"
        ),
    }
