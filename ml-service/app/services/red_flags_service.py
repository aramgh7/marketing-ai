"""
Red Flags Detector — pure logic, no FastAPI/Pydantic imports.
Early kill at 50-100 visits for landing pages ONLY (not Meta creatives).
Threshold = z-score vs niche×placement×device benchmark (vision §15.12.5).
Target FPR < 5%. Labelled 'heuristic' until niche benchmarks have sufficient data.
"""
from __future__ import annotations
from typing import Optional

MIN_VISITS_FOR_FLAG = 50
KILL_PROBABILITY_THRESHOLD = 0.90


def compute_beta_exceedance(
    successes: int,
    trials: int,
    threshold: float,
    n_samples: int = 5000,
) -> float:
    """P(bounce_rate > threshold | data) via Beta posterior Monte Carlo."""
    if trials < 1:
        return 0.0

    alpha = successes + 1.0
    beta_ = (trials - successes) + 1.0

    try:
        import random
        rng = random.Random(0)

        def beta_sample(a: float, b: float) -> float:
            # Gamma-based Beta sampler: O(1) per sample regardless of parameters
            x = rng.gammavariate(a, 1.0)
            y = rng.gammavariate(b, 1.0)
            return x / (x + y)

        exceed = sum(1 for _ in range(n_samples) if beta_sample(alpha, beta_) > threshold)
        return exceed / n_samples
    except Exception:
        observed = successes / max(trials, 1)
        return 1.0 if observed > threshold else 0.0


def get_bounce_threshold(
    niche_benchmark: Optional[dict],
    n_std_above: float = 1.5,
) -> dict:
    """
    Compute kill threshold from niche benchmark.
    Falls back to conservative universal (0.75) and labels as 'heuristic'.
    """
    if niche_benchmark is None or not niche_benchmark.get("is_published"):
        return {"threshold": 0.75, "source": "UNIVERSAL_FALLBACK", "label": "heuristic"}

    mu    = niche_benchmark.get("effect_size", 0.55)
    sigma = (niche_benchmark.get("ci_upper", 0.65) - niche_benchmark.get("ci_lower", 0.45)) / (2 * 1.96)
    threshold = min(mu + n_std_above * sigma, 0.95)

    return {"threshold": round(threshold, 4), "source": "NICHE_BENCHMARK", "label": "niche_calibrated"}


def check_red_flag(
    landing_id: str,
    bounced: int,
    total_visits: int,
    niche_benchmark: Optional[dict] = None,
    is_landing: bool = True,
) -> dict:
    """
    Check if landing should be early-killed.
    ONLY for landings — NOT Meta creatives (different auction mechanism, vision §15.12.5).
    """
    if not is_landing:
        return {
            "should_kill": False,
            "p_exceed": 0.0,
            "reason": "Red flag detection only applies to landings, not Meta creatives",
        }

    if total_visits < MIN_VISITS_FOR_FLAG:
        return {
            "should_kill": False,
            "p_exceed": 0.0,
            "visits": total_visits,
            "reason": f"Insufficient data ({total_visits} < {MIN_VISITS_FOR_FLAG} visits)",
        }

    threshold_info = get_bounce_threshold(niche_benchmark)
    threshold = threshold_info["threshold"]
    p_exceed  = compute_beta_exceedance(bounced, total_visits, threshold)
    should_kill = p_exceed > KILL_PROBABILITY_THRESHOLD

    return {
        "landing_id":   landing_id,
        "should_kill":  should_kill,
        "p_exceed":     round(p_exceed, 4),
        "threshold":    threshold,
        "bounced":      bounced,
        "total_visits": total_visits,
        "label":        threshold_info["label"],
        "source":       threshold_info["source"],
        "reason": (
            f"P(bounce > {threshold:.2f}) = {p_exceed:.3f} "
            f"{'> 0.90 → KILL' if should_kill else '≤ 0.90 → OK'} "
            f"[{threshold_info['label']}]"
        ),
    }
