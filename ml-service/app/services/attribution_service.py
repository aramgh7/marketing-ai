"""
Cross-channel attribution — Shapley + Markov MTA. Phase 7, §17.3.
No FastAPI/Pydantic imports.
"""
from __future__ import annotations

import random
from itertools import combinations


def compute_transition_matrix(
    touchpoint_paths: list[list[str]],
    channels: list[str],
) -> dict[str, dict[str, float]]:
    """Build Markov transition matrix from touch paths."""
    states = channels + ["convert", "null"]
    counts: dict[str, dict[str, int]] = {s: {t: 0 for t in states} for s in states}

    for path in touchpoint_paths:
        if not path:
            continue
        full = path[:]
        if full[-1] != "convert":
            full.append("null")
        for i in range(len(full) - 1):
            frm = full[i]
            to_ = full[i + 1]
            if frm in counts and to_ in counts[frm]:
                counts[frm][to_] += 1

    matrix: dict[str, dict[str, float]] = {}
    for frm, targets in counts.items():
        total = sum(targets.values())
        if total == 0:
            matrix[frm] = {t: 0.0 for t in targets}
        else:
            matrix[frm] = {t: v / total for t, v in targets.items()}

    return matrix


def _conversion_rate_from_matrix(
    matrix: dict[str, dict[str, float]],
    channels: list[str],
    max_steps: int = 100,
) -> float:
    """Estimate overall conversion probability via matrix power iteration."""
    # Start with equal probability on each channel
    if not channels:
        return 0.0
    state_prob = {ch: 1.0 / len(channels) for ch in channels}
    convert_prob = 0.0

    for _ in range(max_steps):
        next_state: dict[str, float] = {ch: 0.0 for ch in channels}
        for state, prob in state_prob.items():
            if prob < 1e-9 or state not in matrix:
                continue
            for target, t_prob in matrix[state].items():
                if target == "convert":
                    convert_prob += prob * t_prob
                elif target in next_state:
                    next_state[target] += prob * t_prob
        state_prob = next_state
        if sum(state_prob.values()) < 1e-9:
            break

    return convert_prob


def compute_removal_effects(
    transition_matrix: dict[str, dict[str, float]],
    channels: list[str],
) -> dict[str, float]:
    """Removal effect for each channel: how much overall CVR drops when it's removed."""
    baseline = _conversion_rate_from_matrix(transition_matrix, channels)
    effects: dict[str, float] = {}
    for ch in channels:
        remaining = [c for c in channels if c != ch]
        removed_rate = _conversion_rate_from_matrix(transition_matrix, remaining)
        effects[ch] = max(0.0, baseline - removed_rate)
    return effects


def compute_markov_attribution(touchpoint_paths: list[list[str]]) -> dict[str, float]:
    """Full Markov chain attribution. Returns fraction per channel."""
    channels: list[str] = []
    seen: set[str] = set()
    for path in touchpoint_paths:
        for ch in path:
            if ch != "convert" and ch not in seen:
                channels.append(ch)
                seen.add(ch)

    if not channels:
        return {}

    converting = [p for p in touchpoint_paths if "convert" in p]
    if not converting:
        return {ch: 0.0 for ch in channels}

    matrix = compute_transition_matrix(touchpoint_paths, channels)
    effects = compute_removal_effects(matrix, channels)
    total_effect = sum(effects.values())
    if total_effect == 0.0:
        equal = 1.0 / len(channels)
        return {ch: round(equal, 6) for ch in channels}

    return {ch: round(e / total_effect, 6) for ch, e in effects.items()}


def compute_shapley_attribution(touchpoint_paths: list[list[str]]) -> dict[str, float]:
    """
    Shapley value attribution via Monte Carlo coalition sampling (n_samples=200, seed=42).
    Returns fraction per channel.
    """
    channels: list[str] = []
    seen: set[str] = set()
    for path in touchpoint_paths:
        for ch in path:
            if ch != "convert" and ch not in seen:
                channels.append(ch)
                seen.add(ch)

    if not channels:
        return {}

    converting = [p for p in touchpoint_paths if "convert" in p]
    if not converting:
        return {ch: 0.0 for ch in channels}

    # Build a simple characteristic function: CVR of paths containing the coalition
    def cvr_coalition(coalition: frozenset[str]) -> float:
        if not coalition:
            return 0.0
        relevant = [p for p in touchpoint_paths if coalition.issubset(set(p))]
        if not relevant:
            return 0.0
        conv = sum(1 for p in relevant if "convert" in p)
        return conv / len(relevant)

    n_ch = len(channels)
    shapley: dict[str, float] = {ch: 0.0 for ch in channels}

    rng = random.Random(42)
    n_samples = 200

    for _ in range(n_samples):
        perm = channels[:]
        rng.shuffle(perm)
        coalition: set[str] = set()
        prev_v = cvr_coalition(frozenset())
        for ch in perm:
            coalition.add(ch)
            new_v = cvr_coalition(frozenset(coalition))
            shapley[ch] += new_v - prev_v
            prev_v = new_v

    total = sum(shapley.values())
    if total == 0.0:
        equal = 1.0 / n_ch
        return {ch: round(equal, 6) for ch in channels}

    return {ch: round(v / total, 6) for ch, v in shapley.items()}


def _last_touch(touchpoint_paths: list[list[str]]) -> dict[str, float]:
    channels: set[str] = set()
    for path in touchpoint_paths:
        for ch in path:
            if ch != "convert":
                channels.add(ch)

    counts: dict[str, int] = {ch: 0 for ch in channels}
    for path in touchpoint_paths:
        if "convert" not in path:
            continue
        # last channel before "convert"
        for ch in reversed(path):
            if ch != "convert":
                counts[ch] = counts.get(ch, 0) + 1
                break

    total = sum(counts.values())
    if total == 0:
        return {ch: 0.0 for ch in channels}
    return {ch: round(v / total, 6) for ch, v in counts.items()}


def _linear_touch(touchpoint_paths: list[list[str]]) -> dict[str, float]:
    channels: set[str] = set()
    for path in touchpoint_paths:
        for ch in path:
            if ch != "convert":
                channels.add(ch)

    counts: dict[str, float] = {ch: 0.0 for ch in channels}
    for path in touchpoint_paths:
        if "convert" not in path:
            continue
        touch_channels = [ch for ch in path if ch != "convert"]
        if not touch_channels:
            continue
        credit = 1.0 / len(touch_channels)
        for ch in touch_channels:
            counts[ch] = counts.get(ch, 0.0) + credit

    total = sum(counts.values())
    if total == 0:
        return {ch: 0.0 for ch in channels}
    return {ch: round(v / total, 6) for ch, v in counts.items()}


def compute_mta_summary(
    touchpoint_paths: list[list[str]],
    method: str = "markov",
) -> dict:
    """Returns attribution summary dict."""
    total_paths = len(touchpoint_paths)
    converting_paths = sum(1 for p in touchpoint_paths if "convert" in p)

    if method == "shapley":
        attribution = compute_shapley_attribution(touchpoint_paths)
    elif method == "last_touch":
        attribution = _last_touch(touchpoint_paths)
    elif method == "linear":
        attribution = _linear_touch(touchpoint_paths)
    else:
        attribution = compute_markov_attribution(touchpoint_paths)

    return {
        "method": method,
        "attribution": attribution,
        "total_paths": total_paths,
        "converting_paths": converting_paths,
    }


# ── Per-session touchpoint attribution (8-10) ─────────────────────────────────

import math
from datetime import datetime, timezone


def _parse_dt(val) -> datetime | None:
    if val is None:
        return None
    if isinstance(val, datetime):
        return val
    try:
        return datetime.fromisoformat(str(val).replace("Z", "+00:00"))
    except Exception:
        return None


def last_touch_attribution(touchpoints: list[dict]) -> list[dict]:
    """100% credit to last touchpoint."""
    if not touchpoints:
        return []
    sorted_tps = sorted(
        touchpoints,
        key=lambda t: _parse_dt(t.get("touched_at")) or datetime.min.replace(tzinfo=timezone.utc),
    )
    result = [{"touchpoint_id": t["id"], "credit": 0.0, "model": "last_touch"} for t in sorted_tps]
    result[-1]["credit"] = 1.0
    return result


def linear_attribution(touchpoints: list[dict]) -> list[dict]:
    """Equal credit across all touchpoints."""
    if not touchpoints:
        return []
    credit = 1.0 / len(touchpoints)
    return [{"touchpoint_id": t["id"], "credit": round(credit, 6), "model": "linear"} for t in touchpoints]


def time_decay_attribution(touchpoints: list[dict], half_life_days: float = 7.0) -> list[dict]:
    """Exponential time decay: closer to conversion gets more credit."""
    if not touchpoints:
        return []
    sorted_tps = sorted(
        touchpoints,
        key=lambda t: _parse_dt(t.get("touched_at")) or datetime.min.replace(tzinfo=timezone.utc),
    )
    conversion_time = _parse_dt(sorted_tps[-1].get("touched_at")) or datetime.now(timezone.utc)
    weights = []
    for tp in sorted_tps:
        tp_time = _parse_dt(tp.get("touched_at")) or conversion_time
        try:
            days_before = (conversion_time - tp_time).total_seconds() / 86400
        except Exception:
            days_before = 0
        weights.append(math.pow(2, -days_before / half_life_days))
    total = sum(weights) or 1.0
    return [
        {"touchpoint_id": tp["id"], "credit": round(w / total, 6), "model": "time_decay"}
        for tp, w in zip(sorted_tps, weights)
    ]


_DEFAULT_CHANNEL_WEIGHTS = {
    "PAID_SOCIAL": 1.4,
    "PAID_SEARCH":  1.2,
    "EMAIL":        1.1,
    "ORGANIC":      0.9,
    "REFERRAL":     0.8,
    "DIRECT":       0.6,
}


def data_driven_attribution(
    touchpoints: list[dict],
    channel_weights: dict | None = None,
) -> list[dict]:
    """Channel-weight based attribution. Falls back to linear if no weights."""
    if not touchpoints:
        return []
    weights_map = channel_weights or _DEFAULT_CHANNEL_WEIGHTS
    raw = [weights_map.get(tp.get("channel", "DIRECT"), 1.0) for tp in touchpoints]
    total = sum(raw) or 1.0
    return [
        {"touchpoint_id": tp["id"], "credit": round(w / total, 6), "model": "data_driven"}
        for tp, w in zip(touchpoints, raw)
    ]


def run_all_models(touchpoints: list[dict], channel_weights: dict | None = None) -> dict:
    """Runs all 4 per-session attribution models."""
    return {
        "last_touch":  last_touch_attribution(touchpoints),
        "linear":      linear_attribution(touchpoints),
        "time_decay":  time_decay_attribution(touchpoints),
        "data_driven": data_driven_attribution(touchpoints, channel_weights),
    }


def aggregate_channel_credit(model_credits: list[dict], touchpoints: list[dict]) -> dict:
    """Aggregates credits per channel for a given model run."""
    tp_map = {t["id"]: t for t in touchpoints}
    result: dict[str, float] = {}
    for row in model_credits:
        tp      = tp_map.get(row["touchpoint_id"], {})
        channel = tp.get("channel", "UNKNOWN")
        result[channel] = result.get(channel, 0.0) + row["credit"]
    return {k: round(v, 4) for k, v in sorted(result.items(), key=lambda x: -x[1])}
