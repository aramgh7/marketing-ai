"""Thompson Sampling strategies — pure numpy, no I/O (roadmap 9-10; vision §6.4).

  sample_arms_standard(arms)            classic Thompson: one Beta draw per arm, argmax wins
  sample_arms_top_two(arms, beta=0.5)   top-two Thompson (Russo 2016): with probability β the
                                        leader is served, otherwise the first *different* arm that
                                        leads a re-draw (the challenger) — spends exploration on the
                                        runner-up instead of on hopeless arms
  select_sampler(mode)                  "standard" | "top_two" → function

Both return (best_arm, candidates) in exactly the format of thompson_service.sample_arms
(arch v1.1 §4.6): candidates carry variant_id, arm_id, propensity, sample_value, alpha, beta.
The propensity is a Monte Carlo estimate of P(arm is chosen by *this* strategy), so the
off-policy evaluation stays valid whichever sampler a tenant runs (tenants.settings.ts_mode).
"""
from __future__ import annotations

from typing import Callable

import numpy as np

N_PROPENSITY = 200
_MAX_REDRAWS = 50


def _rng(rng: np.random.Generator | None) -> np.random.Generator:
    return rng if rng is not None else np.random.default_rng()


def _draw(arms: list[dict], rng: np.random.Generator) -> tuple[int, np.ndarray]:
    vals = np.array([rng.beta(a["alpha"], a["beta"]) for a in arms], dtype=float)
    return int(np.argmax(vals)), vals


def _candidates(arms: list[dict], sample_values: np.ndarray, win_counts: np.ndarray, n: int) -> list[dict]:
    return [
        {
            "variant_id":   arm["variant_id"],
            "arm_id":       arm["id"],
            "propensity":   round(float(win_counts[i]) / n, 4),
            "sample_value": round(float(sample_values[i]), 6),
            "alpha":        arm["alpha"],
            "beta":         arm["beta"],
        }
        for i, arm in enumerate(arms)
    ]


def sample_arms_standard(
    arms: list[dict], n_propensity: int = N_PROPENSITY, rng: np.random.Generator | None = None
) -> tuple[dict | None, list[dict]]:
    """Classic Thompson sampling with an injectable rng (same semantics as thompson_service.sample_arms)."""
    if not arms:
        return None, []
    g = _rng(rng)
    best_i, vals = _draw(arms, g)
    best = {**arms[best_i], "sample_value": float(vals[best_i])}

    wins = np.zeros(len(arms))
    for _ in range(n_propensity):
        i, _ = _draw(arms, g)
        wins[i] += 1
    return best, _candidates(arms, vals, wins, n_propensity)


def _top_two_pick(arms: list[dict], beta: float, rng: np.random.Generator) -> tuple[int, np.ndarray]:
    """One top-two decision: returns (chosen index, the first draw's sample values)."""
    leader, vals = _draw(arms, rng)
    if len(arms) == 1 or rng.random() < beta:
        return leader, vals
    for _ in range(_MAX_REDRAWS):
        challenger, _ = _draw(arms, rng)
        if challenger != leader:
            return challenger, vals
    # the leader dominates every re-draw — serve it (Russo: fall back to the leader)
    return leader, vals


def sample_arms_top_two(
    arms: list[dict],
    beta: float = 0.5,
    n_propensity: int = N_PROPENSITY,
    rng: np.random.Generator | None = None,
) -> tuple[dict | None, list[dict]]:
    """Top-two Thompson sampling. β = 1.0 reduces to the standard sampler."""
    if not arms:
        return None, []
    if not 0.0 <= beta <= 1.0:
        raise ValueError("beta must be in [0, 1]")
    g = _rng(rng)
    chosen, vals = _top_two_pick(arms, beta, g)
    best = {**arms[chosen], "sample_value": float(vals[chosen])}

    wins = np.zeros(len(arms))
    for _ in range(n_propensity):
        i, _ = _top_two_pick(arms, beta, g)
        wins[i] += 1
    return best, _candidates(arms, vals, wins, n_propensity)


SAMPLERS: dict[str, Callable[..., tuple[dict | None, list[dict]]]] = {
    "standard": sample_arms_standard,
    "top_two":  sample_arms_top_two,
}


def select_sampler(mode: str | None) -> Callable[..., tuple[dict | None, list[dict]]]:
    """Resolve tenants.settings.ts_mode → sampler. Unknown / empty → standard."""
    return SAMPLERS.get((mode or "standard").strip().lower(), sample_arms_standard)
