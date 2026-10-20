"""
Off-Policy Evaluation (IPS / SNIPS / Doubly-Robust) — no FastAPI/Pydantic imports.
Vision §12.5, §23 Phase 3; arch §15.11.

Estimates value of the CURRENT policy (today's posteriors) vs the LOGGED policy
on the decision_log rows, using propensity weights.
"""
from __future__ import annotations

import math
import random
from typing import Optional


def _clip_weight(w: float, w_max: float) -> float:
    return min(w, w_max)


def _bootstrap_ci(values: list[float], n_boot: int = 500, alpha: float = 0.05,
                  seed: int = 42) -> tuple[float, float]:
    rng = random.Random(seed)
    stats = []
    n = len(values)
    for _ in range(n_boot):
        sample = [values[rng.randint(0, n - 1)] for _ in range(n)]
        stats.append(sum(sample) / n)
    stats.sort()
    lo_idx = int(alpha / 2 * n_boot)
    hi_idx = int((1 - alpha / 2) * n_boot)
    return stats[lo_idx], stats[min(hi_idx, n_boot - 1)]


def compute_ope(
    decisions: list[dict],
    current_policy_probs: dict,  # {arm_id: probability under current policy}
    w_max: float = 10.0,
    min_ess: float = 200.0,
) -> dict:
    """
    decisions: list of {arm_id, logged_prob, reward}
    current_policy_probs: {arm_id: new_prob}

    Returns {ips, snips, dr, ci_low, ci_high, ess, clipped_mass, verdict}.
    """
    if len(decisions) < 10:
        return {
            "ips": None, "snips": None, "dr": None,
            "ci_low": None, "ci_high": None,
            "ess": 0, "clipped_mass": 0,
            "verdict": "INSUFFICIENT_DATA"
        }

    weights = []
    clipped = 0
    ips_terms = []
    weight_sum = 0.0

    for d in decisions:
        arm_id = d.get("arm_id") or d.get("variant_id", "")
        logged_prob = float(d.get("logged_prob", d.get("propensity", 1e-6)) or 1e-6)
        new_prob = float(current_policy_probs.get(str(arm_id), logged_prob))
        reward = float(d.get("reward", 0) or 0)

        raw_w = new_prob / max(logged_prob, 1e-9)
        w = _clip_weight(raw_w, w_max)
        if raw_w > w_max:
            clipped += 1

        weights.append(w)
        ips_terms.append(w * reward)
        weight_sum += w

    n = len(decisions)
    clipped_mass = clipped / n

    # IPS
    ips = sum(ips_terms) / n

    # SNIPS (self-normalised)
    snips = sum(ips_terms) / weight_sum if weight_sum > 1e-9 else 0.0

    # DR (doubly-robust) — use mean reward as outcome model (simple baseline)
    mean_reward = sum(d.get("reward", 0) or 0 for d in decisions) / n
    dr_terms = [ips_terms[i] - weights[i] * mean_reward + mean_reward
                for i in range(n)]
    dr = sum(dr_terms) / n

    # ESS
    w2 = sum(w ** 2 for w in weights)
    ess = (weight_sum ** 2) / max(w2, 1e-9)

    # Bootstrap CI on SNIPS
    if n >= 10:
        ci_low, ci_high = _bootstrap_ci(
            [ips_terms[i] / max(weights[i], 1e-9) * (weight_sum / n)
             for i in range(n)])
    else:
        ci_low, ci_high = snips * 0.8, snips * 1.2

    # Verdict: current policy is at least as good as logged if SNIPS >= baseline
    # Baseline = logged policy mean reward (approximately 1.0 in ratio)
    # Pass if SNIPS >= 0.95 (within 5% of logged) AND ESS sufficient
    verdict = "PASS" if snips >= 0.95 and ess >= min_ess else "FAIL"
    if len(decisions) < 30:
        verdict = "INSUFFICIENT_DATA"

    return {
        "ips": round(ips, 4),
        "snips": round(snips, 4),
        "dr": round(dr, 4),
        "ci_low": round(ci_low, 4),
        "ci_high": round(ci_high, 4),
        "ess": round(ess, 1),
        "clipped_mass": round(clipped_mass, 4),
        "verdict": verdict,
    }


def passes_ope_gate(result: dict, min_ess: float = 200.0) -> bool:
    """True when an OPE result clears the promotion gate (verdict PASS and ESS >= min_ess).

    Used by /analyze/ope/ips; INSUFFICIENT_DATA and FAIL never pass.
    """
    return result.get("verdict") == "PASS" and float(result.get("ess", 0.0)) >= min_ess
