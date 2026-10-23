"""
Segment random effects — audience × atom interactions with partial pooling (vision §15.4 §3-1b).
Pure logic, zero FastAPI/Pydantic imports.
"""
from __future__ import annotations
import math

TAU_SQUARED = 0.25  # prior variance; precision=4 → 50% shrinkage at n≈4, meaningful pull below n≈20
# Previous value (4.0) gave w≈0.976 at n=10 — effectively disabled partial pooling
MIN_N = 30


def compute_shrinkage_weight(n_observations: int, tau_squared: float = TAU_SQUARED) -> float:
    """Weight for local estimate vs global prior. 0 = full shrinkage, 1 = no shrinkage."""
    return n_observations / (n_observations + 1.0 / tau_squared)


def compute_segment_effect(
    segment_conversions: int,
    segment_impressions: int,
    global_mean: float,
    min_n: int = MIN_N,
) -> dict:
    """
    Bayesian partial-pooling estimate for a segment's conversion rate.
    Falls back to global_mean with wide CI when n < min_n.
    """
    if segment_impressions < min_n:
        return {
            "estimate":       global_mean,
            "credible_lower": max(0.0, global_mean * 0.8),
            "credible_upper": min(1.0, global_mean * 1.2),
            "shrinkage":      1.0,
            "note":           f"n={segment_impressions} < {min_n}; using global prior",
        }

    local_mean = segment_conversions / segment_impressions
    w          = compute_shrinkage_weight(segment_impressions)
    estimate   = w * local_mean + (1.0 - w) * global_mean

    se = math.sqrt(estimate * (1.0 - estimate) / max(segment_impressions, 1))
    z  = 1.282  # 80% credible interval
    return {
        "estimate":       round(estimate, 6),
        "credible_lower": round(max(0.0, estimate - z * se), 6),
        "credible_upper": round(min(1.0, estimate + z * se), 6),
        "shrinkage":      round(1.0 - w, 4),
        "local_mean":     round(local_mean, 6),
        "n":              segment_impressions,
    }


def rank_atoms_by_segment(
    atom_segment_data: list[dict],
    global_mean: float,
) -> list[dict]:
    """
    Returns atoms ranked by shrinkage-adjusted posterior estimate per segment.
    Input: [{"atom_id", "segment", "conversions", "impressions"}]
    """
    results = []
    for row in atom_segment_data:
        effect = compute_segment_effect(
            int(row.get("conversions", 0)),
            int(row.get("impressions", 0)),
            global_mean,
        )
        results.append({
            "atom_id": row.get("atom_id"),
            "segment": row.get("segment"),
            **effect,
        })
    results.sort(key=lambda x: (-x["estimate"], str(x.get("segment", ""))))
    return results
