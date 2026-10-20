"""Expected Value of Information for experiment ranking — pure numpy (roadmap 9-10; vision §6.4).

For each arm: what would we gain, in expected best-arm value, from observing `n_extra` more sessions
on it? Monte Carlo over the posterior: draw the arm's true rate from Beta(α, β), simulate the extra
outcomes, update, and take E[max over arm means after] − max over arm means now. Arms whose posterior
is already tight (or hopeless) earn little; uncertain contenders earn the most. `rank_experiments`
turns the ranking into an exploration budget with a 5 % floor per arm.
"""
from __future__ import annotations

import numpy as np

BUDGET_FLOOR = 0.05


def expected_value_of_information(
    arms: list[dict], n_extra: int = 100, n_mc: int = 2000, rng: np.random.Generator | None = None
) -> list[dict]:
    """Returns rows sorted by evi desc: {arm_id, variant_id, evi, p_best_now, p_best_after_mean}."""
    if not arms:
        return []
    g = rng if rng is not None else np.random.default_rng()
    alpha = np.array([float(a["alpha"]) for a in arms])
    beta = np.array([float(a["beta"]) for a in arms])
    means_now = alpha / (alpha + beta)
    best_now = float(means_now.max())

    # P(best now) by Monte Carlo of the current posteriors
    draws = g.beta(alpha[None, :], beta[None, :], size=(n_mc, len(arms)))
    p_best_now = np.bincount(np.argmax(draws, axis=1), minlength=len(arms)) / n_mc

    rows = []
    for i, arm in enumerate(arms):
        true_rate = g.beta(alpha[i], beta[i], size=n_mc)
        successes = g.binomial(n_extra, true_rate)
        new_mean_i = (alpha[i] + successes) / (alpha[i] + beta[i] + n_extra)
        others = np.delete(means_now, i)
        other_best = float(others.max()) if others.size else -np.inf
        best_after = np.maximum(new_mean_i, other_best)
        evi = float(best_after.mean() - best_now)
        rows.append({
            "arm_id": arm.get("id") or arm.get("arm_id"),
            "variant_id": arm.get("variant_id"),
            "evi": round(max(evi, 0.0), 6),
            "p_best_now": round(float(p_best_now[i]), 4),
            "p_best_after_mean": round(float((new_mean_i >= other_best).mean()), 4),
            "mean_now": round(float(means_now[i]), 6),
        })
    rows.sort(key=lambda r: r["evi"], reverse=True)
    return rows


def rank_experiments(candidates: list[dict], budget_sessions: int, floor: float = BUDGET_FLOOR) -> list[dict]:
    """Allocate `budget_sessions` proportionally to evi with a floor share per candidate."""
    if not candidates or budget_sessions <= 0:
        return []
    n = len(candidates)
    evis = np.array([max(float(c.get("evi", 0.0)), 0.0) for c in candidates])
    if evis.sum() <= 0:
        shares = np.full(n, 1.0 / n)
    else:
        shares = evis / evis.sum()
    floor = min(floor, 1.0 / n)                     # the floor must be feasible
    for _ in range(2 * n):                          # clamp + renormalise until every share >= floor
        shares = np.maximum(shares, floor)
        shares = shares / shares.sum()
        if np.all(shares >= floor - 1e-12):
            break
    alloc = np.floor(shares * budget_sessions).astype(int)
    remainder = int(budget_sessions - alloc.sum())
    for i in np.argsort(-shares)[:remainder]:       # hand the rounding remainder to the top shares
        alloc[i] += 1
    out = []
    for c, sh, a in zip(candidates, shares, alloc):
        out.append({**c, "budget_share": round(float(sh), 4), "sessions": int(a)})
    out.sort(key=lambda r: r["sessions"], reverse=True)
    return out
