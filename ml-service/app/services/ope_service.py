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


def _bootstrap_snips_ci(weights: list[float], rewards: list[float], n_boot: int = 500,
                        alpha: float = 0.05, seed: int = 42) -> tuple[float, float]:
    """Percentile bootstrap of the self-normalised estimator Σwr/Σw over resampled decisions.

    Audit 6.2: the previous version bootstrapped per-decision values in which the weights cancelled,
    i.e. a CI for the logged reward rate rather than for SNIPS.
    """
    rng = random.Random(seed)
    n = len(weights)
    stats = []
    for _ in range(n_boot):
        sw = swr = 0.0
        for _k in range(n):
            i = rng.randint(0, n - 1)
            sw += weights[i]
            swr += weights[i] * rewards[i]
        stats.append(swr / sw if sw > 1e-9 else 0.0)
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
    rewards = [float(d.get("reward", 0) or 0) for d in decisions]
    if n >= 10:
        ci_low, ci_high = _bootstrap_snips_ci(weights, rewards)
    else:
        ci_low, ci_high = snips * 0.8, snips * 1.2

    # Verdict (audit 6.1): SNIPS is an absolute reward *rate* of the current policy (typically
    # 0.03-0.05), so it is compared with the logged policy's mean reward, not with 0.95.
    # PASS when the current policy keeps >= 95 % of the logged reward rate AND ESS is sufficient.
    baseline = mean_reward
    verdict = "PASS" if snips >= 0.95 * baseline and ess >= min_ess else "FAIL"
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
        "logged_mean_reward": round(mean_reward, 4),
        "verdict": verdict,
    }


def passes_ope_gate(result: dict, min_ess: float = 200.0) -> bool:
    """True when an OPE result clears the promotion gate (verdict PASS and ESS >= min_ess).

    Used by /analyze/ope/ips; INSUFFICIENT_DATA and FAIL never pass.
    """
    return result.get("verdict") == "PASS" and float(result.get("ess", 0.0)) >= min_ess
