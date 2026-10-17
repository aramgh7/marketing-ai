"""
Reg CF Waitlist value estimation (vision §19.18.8; arch §16.20).
Pure logic — zero FastAPI/Pydantic imports.

⚠️ FROZEN — OI-3: audience NOT activated until counsel answers.
Computes weekly waitlist value estimate with credible interval.
"""
from __future__ import annotations
import math


def estimate_waitlist_value(
    n_leads:          int,
    p_convert_regcf:  float = 0.05,  # prior: 5% conversion for Reg CF (very different from 506(c))
    avg_ticket_regcf: float = 10_000.0,  # prior: avg Reg CF ticket $10K
    confidence:       float = 0.80,
) -> dict:
    """
    Expected waitlist value = n_leads × p_convert × avg_ticket.
    CI from Beta posterior on p_convert: alpha=p*n+1, beta=(1-p)*n+1.
    Returns: {estimated_value, ci_lower, ci_upper, n_leads, ...}
    """
    estimated_value = n_leads * p_convert_regcf * avg_ticket_regcf

    # Beta posterior credible interval for p_convert
    alpha = p_convert_regcf * n_leads + 1
    beta  = (1 - p_convert_regcf) * n_leads + 1
    mean  = alpha / (alpha + beta)
    se    = math.sqrt(mean * (1 - mean) / max(n_leads, 1))
    z     = 1.282  # 80% CI

    p_lower = max(0.0, mean - z * se)
    p_upper = min(1.0, mean + z * se)

    return {
        "n_leads":          n_leads,
        "p_convert_regcf":  round(p_convert_regcf, 4),
        "avg_ticket_regcf": avg_ticket_regcf,
        "estimated_value":  round(estimated_value, 2),
        "ci_lower":         round(p_lower * n_leads * avg_ticket_regcf, 2),
        "ci_upper":         round(p_upper * n_leads * avg_ticket_regcf, 2),
        "confidence_level": confidence,
        "frozen_note":      "OI-3: REGCF_WAITLIST audience NOT activated until counsel sign-off",
    }
