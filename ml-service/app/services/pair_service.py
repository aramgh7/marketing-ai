"""
Creative → landing pairs as arms (roadmap Phase 11-5, `.ai/creative-publishing.md`).

Pure logic: `summarise_pairs` turns raw (creative, variant, decisions, rewards) rows into Beta(1,1)
posteriors; `win_probabilities` estimates P(pair is best) by Monte Carlo over those posteriors.
DB access lives in the router (`GET /decide/pairs`).
"""
from __future__ import annotations

from typing import Optional

import numpy as np

NO_CREATIVE_TITLE = "(no creative)"
# Rewards counted as a conversion for the pair view: form submit and every later funnel signal.
CONVERSION_REWARD_TYPES: tuple[str, ...] = (
    "FORM_SUBMIT", "CALL_ANSWERED", "MEETING_ATTENDED", "CONVERSION", "PURCHASE",
)


def _as_int(v) -> int:
    try:
        return int(v or 0)
    except (TypeError, ValueError):
        return 0


def summarise_pairs(rows: list[dict], min_decisions: int = 1) -> list[dict]:
    """rows: {creative_id, creative_title, creative_code, variant_id, variant_name, decisions, rewards}.

    Rows sharing (creative_id, variant_id) are merged. Pairs with fewer than `min_decisions`
    decisions are dropped. Output sorted by posterior_mean desc, then decisions desc.
    """
    merged: dict[tuple[Optional[str], str], dict] = {}
    for r in rows:
        creative_id = r.get("creative_id")
        creative_id = str(creative_id) if creative_id else None
        variant_id = str(r.get("variant_id") or "")
        if not variant_id:
            continue
        key = (creative_id, variant_id)
        cur = merged.get(key)
        if cur is None:
            cur = {
                "creative_id": creative_id,
                "creative_title": (r.get("creative_title") or (NO_CREATIVE_TITLE if creative_id is None else "")),
                "creative_code": r.get("creative_code") if creative_id else None,
                "variant_id": variant_id,
                "variant_name": r.get("variant_name") or "",
                "decisions": 0,
                "rewards": 0,
            }
            merged[key] = cur
        cur["decisions"] += _as_int(r.get("decisions"))
        cur["rewards"] += _as_int(r.get("rewards"))

    out: list[dict] = []
    for p in merged.values():
        if p["decisions"] < max(1, min_decisions):
            continue
        rewards = min(p["rewards"], p["decisions"])
        alpha = 1.0 + rewards
        beta = 1.0 + p["decisions"] - rewards
        p["rewards"] = rewards
        p["cvr"] = round(rewards / p["decisions"], 4) if p["decisions"] else 0.0
        p["alpha"] = alpha
        p["beta"] = beta
        p["posterior_mean"] = round(alpha / (alpha + beta), 4)
        p["win_probability"] = 0.0
        out.append(p)

    out.sort(key=lambda x: (-x["posterior_mean"], -x["decisions"]))
    return out


def win_probabilities(pairs: list[dict], draws: int = 200, rng: Optional[np.random.Generator] = None) -> list[dict]:
    """Monte Carlo P(pair wins) across all given pairs; fills `win_probability` in place and returns the list."""
    if not pairs:
        return pairs
    rng = rng or np.random.default_rng()
    if len(pairs) == 1:
        pairs[0]["win_probability"] = 1.0
        return pairs
    a = np.array([p["alpha"] for p in pairs], dtype=float)
    b = np.array([p["beta"] for p in pairs], dtype=float)
    samples = rng.beta(a, b, size=(draws, len(pairs)))
    winners = np.argmax(samples, axis=1)
    counts = np.bincount(winners, minlength=len(pairs))
    for i, p in enumerate(pairs):
        p["win_probability"] = round(float(counts[i]) / draws, 4)
    return pairs
