"""
A/B Experiment Lifecycle — Bayesian stopping rules (vision §3; arch §16.9).
Pure logic — zero FastAPI/Pydantic imports.

Stopping criteria:
- Auto-conclude: P(best arm > all others) > auto_stop_p AND ESS >= min_ess
- Auto-pause: P(arm worse than control) > 0.95 AND ESS >= 50
ESS approximated as sum(alpha + beta - 2) across arms.
"""
from __future__ import annotations
import random


def estimate_ess(
    arms: list[dict],
    prior_alpha: float = 1.0,
    prior_beta: float = 1.0,
) -> float:
    """
    ESS approximation: sum of (alpha + beta - prior_alpha - prior_beta) across arms.

    Subtracts the actual prior parameters so informed priors (alpha > 1 or beta > 1)
    are not counted as observed data.  For flat Beta(1,1) priors the result is
    identical to the old ``- 2`` formula.
    """
    return sum(
        max(float(a.get("alpha", 1.0)) + float(a.get("beta", 1.0)) - prior_alpha - prior_beta, 0)
        for a in arms
    )


def thompson_best_arm(arms: list[dict], n_samples: int = 10_000) -> list[dict]:
    """
    Returns list of {variant_id, win_probability, sample_mean} for all arms.
    win_probability = fraction of MC trials this arm was best.
    """
    if not arms:
        return []

    wins: dict[str, int] = {a["variant_id"]: 0 for a in arms}
    for _ in range(n_samples):
        samples = {
            a["variant_id"]: random.betavariate(
                max(float(a.get("alpha", 1.0)), 1.0),
                max(float(a.get("beta",  1.0)), 1.0),
            )
            for a in arms
        }
        best = max(samples, key=lambda k: samples[k])
        wins[best] += 1

    results = []
    for a in arms:
        vid   = a["variant_id"]
        alpha = max(float(a.get("alpha", 1.0)), 1.0)
        beta  = max(float(a.get("beta",  1.0)), 1.0)
        results.append({
            "variant_id":      vid,
            "win_probability": round(wins[vid] / n_samples, 4),
            "sample_mean":     round(alpha / (alpha + beta), 4),
            "alpha":           alpha,
            "beta":            beta,
        })

    results.sort(key=lambda x: x["win_probability"], reverse=True)
    return results


def should_conclude(
    arms: list[dict],
    min_ess: int = 200,
    auto_stop_p: float = 0.95,
) -> dict:
    """
    Checks whether the experiment should be concluded.
    Returns {should_conclude, winner_variant_id, reason, ess, best_win_prob}.
    """
    ess = estimate_ess(arms)
    if ess < min_ess:
        return {
            "should_conclude":   False,
            "reason":            f"ESS {ess:.0f} < min_ess {min_ess}",
            "ess":               ess,
            "winner_variant_id": None,
            "best_win_prob":     None,
        }

    ranked = thompson_best_arm(arms)
    if not ranked:
        return {"should_conclude": False, "reason": "no arms", "ess": ess}

    best = ranked[0]
    if best["win_probability"] >= auto_stop_p:
        return {
            "should_conclude":   True,
            "winner_variant_id": best["variant_id"],
            "best_win_prob":     best["win_probability"],
            "reason":            f"P(best)={best['win_probability']:.3f} >= {auto_stop_p}",
            "ess":               ess,
            "ranked":            ranked,
        }

    return {
        "should_conclude":   False,
        "reason":            f"P(best)={best['win_probability']:.3f} < {auto_stop_p}",
        "ess":               ess,
        "best_win_prob":     best["win_probability"],
        "ranked":            ranked,
    }


def arm_worse_than_control(
    arm_alpha: float,
    arm_beta:  float,
    ctrl_alpha: float,
    ctrl_beta:  float,
    n_samples: int = 2000,
) -> float:
    """Monte Carlo P(arm < control). Used for auto-pause."""
    worse = 0
    for _ in range(n_samples):
        a = random.betavariate(max(arm_alpha, 1.0), max(arm_beta, 1.0))
        c = random.betavariate(max(ctrl_alpha, 1.0), max(ctrl_beta, 1.0))
        if a < c:
            worse += 1
    return worse / n_samples


def check_auto_pause(
    arms: list[dict],
    min_ess_pause: int = 50,
    p_threshold: float = 0.95,
) -> list[dict]:
    """
    Returns list of {variant_id, p_worse, should_pause} for non-control arms.
    Flags arm for pause if P(arm < control) > p_threshold AND ESS >= min_ess_pause.
    """
    control = next((a for a in arms if a.get("is_control")), None)
    if control is None:
        return []

    ctrl_alpha = max(float(control.get("alpha", 1.0)), 1.0)
    ctrl_beta  = max(float(control.get("beta",  1.0)), 1.0)

    results = []
    for arm in arms:
        if arm.get("is_control"):
            continue
        arm_alpha = max(float(arm.get("alpha", 1.0)), 1.0)
        arm_beta  = max(float(arm.get("beta",  1.0)), 1.0)
        arm_ess   = arm_alpha + arm_beta - 2

        if arm_ess < min_ess_pause:
            results.append({
                "variant_id":   arm["variant_id"],
                "p_worse":      None,
                "should_pause": False,
                "reason":       f"ESS {arm_ess:.0f} < {min_ess_pause}",
            })
            continue

        p_worse = arm_worse_than_control(arm_alpha, arm_beta, ctrl_alpha, ctrl_beta)
        results.append({
            "variant_id":   arm["variant_id"],
            "p_worse":      round(p_worse, 4),
            "should_pause": p_worse > p_threshold,
            "reason":       f"P(worse)={p_worse:.3f}",
        })

    return results
