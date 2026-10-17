"""
Niche feature store — pure logic, no FastAPI/Pydantic imports.
Stores effect sizes + CI. Never publishes cells with < 5 tenants.
Uses ONLY first-party events (not Meta API metrics) per vision §25 p.19.
"""
from __future__ import annotations
import math
from typing import Optional

MIN_TENANTS_FOR_PUBLISH = 5


def compute_cohens_d(
    mean1: float, std1: float, n1: int,
    mean2: float, std2: float, n2: int,
) -> dict:
    """Cohen's d effect size between two groups. 95% CI using normal approximation."""
    if n1 < 2 or n2 < 2:
        return {"effect_size": 0.0, "ci_lower": -1.0, "ci_upper": 1.0, "reliable": False}

    pooled_var = ((n1 - 1) * std1**2 + (n2 - 1) * std2**2) / (n1 + n2 - 2)
    pooled_sd  = math.sqrt(max(pooled_var, 1e-12))
    d = (mean1 - mean2) / pooled_sd

    se = math.sqrt((n1 + n2) / (n1 * n2) + d**2 / (2 * (n1 + n2 - 2)))
    z95 = 1.96
    return {
        "effect_size": round(d, 4),
        "ci_lower":    round(d - z95 * se, 4),
        "ci_upper":    round(d + z95 * se, 4),
        "reliable":    True,
    }


def should_publish(n_tenants: int) -> bool:
    """Cell is hidden if fewer than MIN_TENANTS_FOR_PUBLISH tenants contribute."""
    return n_tenants >= MIN_TENANTS_FOR_PUBLISH


def build_benchmark_record(
    niche: str,
    placement: str,
    device_type: str,
    metric: str,
    niche_mean: float, niche_std: float, niche_n: int,
    global_mean: float, global_std: float, global_n: int,
    n_tenants: int,
    source: str = "FIRST_PARTY",
) -> dict:
    """
    Build a niche benchmark record.
    is_published = False if n_tenants < 5 (vision §15.12.7 aggregate floor).
    source must be FIRST_PARTY until Meta Platform Terms legal review clears (§25 p.19).
    """
    effect = compute_cohens_d(niche_mean, niche_std, niche_n, global_mean, global_std, global_n)
    published = should_publish(n_tenants)

    return {
        "niche":           niche,
        "placement":       placement,
        "device_type":     device_type,
        "metric":          metric,
        "effect_size":     effect["effect_size"],
        "ci_lower":        effect["ci_lower"],
        "ci_upper":        effect["ci_upper"],
        "n_tenants":       n_tenants,
        "n_observations":  niche_n,
        "is_published":    published,
        "source":          source,
        "suppressed_reason": None if published else f"n_tenants={n_tenants} < {MIN_TENANTS_FOR_PUBLISH}",
    }
