"""
GLM interaction terms via Bayes factor approximation — pure logic, no FastAPI/Pydantic.
vision §15.8 task 1: interaction terms by Bayes factor or BART when BF > 3.
"""
from __future__ import annotations
import math


def compute_bayes_factor_approx(
    n: int,
    r_squared_full: float,
    r_squared_restricted: float,
    k_extra_params: int = 1,
) -> dict:
    """
    BIC-based Bayes factor approximation for interaction term inclusion.
    BF ≈ exp(-0.5 * ΔBIC).
    BF > 3 → INCLUDE; BF < 1/3 → EXCLUDE; otherwise AMBIGUOUS.
    """
    if n < k_extra_params + 2:
        return {
            "bayes_factor": 1.0,
            "decision": "AMBIGUOUS",
            "delta_bic": 0.0,
            "r_squared_full": r_squared_full,
            "r_squared_restricted": r_squared_restricted,
            "threshold": 3.0,
            "reason": "Insufficient data",
        }

    rss_full       = max(1 - r_squared_full,       1e-10)
    rss_restricted = max(1 - r_squared_restricted, 1e-10)

    k_full       = k_extra_params + 1
    k_restricted = 1

    bic_full       = n * math.log(rss_full       / n) + k_full       * math.log(n)
    bic_restricted = n * math.log(rss_restricted / n) + k_restricted * math.log(n)

    delta_bic = bic_restricted - bic_full
    bf = math.exp(0.5 * delta_bic)

    if bf > 3.0:
        decision = "INCLUDE"
    elif bf < 1.0 / 3.0:
        decision = "EXCLUDE"
    else:
        decision = "AMBIGUOUS"

    return {
        "bayes_factor":          round(bf, 4),
        "decision":              decision,
        "delta_bic":             round(delta_bic, 4),
        "r_squared_full":        r_squared_full,
        "r_squared_restricted":  r_squared_restricted,
        "threshold":             3.0,
        "reason":                f"BF={bf:.2f} → {decision} (BF > 3 to include, vision §15.8 task 1)",
    }


def rank_interactions_by_bf(
    interaction_candidates: list[dict],
) -> list[dict]:
    """Rank candidate interaction terms by BF descending (INCLUDE first)."""
    results = []
    for c in interaction_candidates:
        bf_result = compute_bayes_factor_approx(
            c.get("n", 100),
            c.get("r2_full", 0.5),
            c.get("r2_restricted", 0.4),
        )
        results.append({**c, **bf_result})
    return sorted(results, key=lambda x: x["bayes_factor"], reverse=True)
