"""
Bayesian Media Mix Modeling (MMM) — Phase 7, §17.3.
Adstock + Hill saturation. PyMC imported lazily (not at module level).
"""
from __future__ import annotations


def apply_adstock(spends: list[float], decay_rate: float) -> list[float]:
    """Geometric adstock: adstock[t] = spend[t] + decay_rate * adstock[t-1]."""
    if not spends:
        return []
    result: list[float] = []
    prev = 0.0
    for s in spends:
        val = s + decay_rate * prev
        result.append(val)
        prev = val
    return result


def apply_saturation(
    adstocked: list[float],
    half_saturation: float,
    slope: float = 1.0,
) -> list[float]:
    """Hill saturation: f(x) = x^slope / (half_saturation^slope + x^slope)."""
    if not adstocked:
        return []
    hs = half_saturation ** slope if half_saturation > 0 else 1.0
    result: list[float] = []
    for x in adstocked:
        if x <= 0.0:
            result.append(0.0)
        else:
            xs = x ** slope
            result.append(xs / (hs + xs))
    return result


def compute_channel_contributions(
    spend_data: dict[str, list[float]],
    contributions: dict[str, float],
) -> dict[str, list[float]]:
    """Per-channel contribution time series (not normalised across channels)."""
    result: dict[str, list[float]] = {}
    for channel, spends in spend_data.items():
        weight = contributions.get(channel, 0.0)
        result[channel] = [s * weight for s in spends]
    return result


def _expected_roi(
    saturation_params: dict[str, dict],
    allocation: dict[str, float],
) -> float:
    """Compute expected total conversions given a budget split."""
    total = 0.0
    for channel, budget in allocation.items():
        p = saturation_params.get(channel, {})
        hs = p.get("half_saturation", 5000.0)
        slope = p.get("slope", 1.0)
        decay = p.get("decay_rate", 0.5)
        roi_scale = p.get("roi_scale", 1.0)
        # Single-period adstock for a lump budget
        adstocked = [budget / (1.0 - decay)] if decay < 1.0 else [budget]
        saturated = apply_saturation(adstocked, hs, slope)
        total += saturated[0] * roi_scale
    return total


def optimize_budget_allocation(
    saturation_params: dict[str, dict],
    total_budget: float,
    n_steps: int = 20,
) -> dict:
    """
    Grid search over budget splits to maximise expected conversions.
    Returns {"allocation": {channel: budget}, "expected_roi": float, "curve": [...]}
    """
    channels = list(saturation_params.keys())
    if not channels:
        return {"allocation": {}, "expected_roi": 0.0, "curve": []}

    if len(channels) == 1:
        ch = channels[0]
        roi = _expected_roi(saturation_params, {ch: total_budget})
        return {
            "allocation": {ch: total_budget},
            "expected_roi": round(roi, 4),
            "curve": [{"budget": total_budget, "expected_roi": round(roi, 4)}],
        }

    # For 2 channels: grid search; generalises to N via greedy marginal increments
    if len(channels) == 2:
        ch_a, ch_b = channels[0], channels[1]
        best_roi = -1.0
        best_alloc: dict[str, float] = {}
        curve = []
        for i in range(n_steps + 1):
            frac = i / n_steps
            alloc = {ch_a: frac * total_budget, ch_b: (1 - frac) * total_budget}
            roi = _expected_roi(saturation_params, alloc)
            curve.append({"budget_frac_a": round(frac, 3), "expected_roi": round(roi, 4)})
            if roi > best_roi:
                best_roi = roi
                best_alloc = {k: round(v, 2) for k, v in alloc.items()}
        return {"allocation": best_alloc, "expected_roi": round(best_roi, 4), "curve": curve}

    # N channels: greedy marginal increment (each step allocates total_budget/n_steps)
    step_size = total_budget / n_steps
    allocation = {ch: 0.0 for ch in channels}
    for _ in range(n_steps):
        best_channel = None
        best_marginal = -1.0
        for ch in channels:
            test = dict(allocation)
            test[ch] += step_size
            marginal = _expected_roi(saturation_params, test) - _expected_roi(saturation_params, allocation)
            if marginal > best_marginal:
                best_marginal = marginal
                best_channel = ch
        if best_channel:
            allocation[best_channel] += step_size

    roi = _expected_roi(saturation_params, allocation)
    return {
        "allocation": {k: round(v, 2) for k, v in allocation.items()},
        "expected_roi": round(roi, 4),
        "curve": [],
    }


def build_mmm_summary(
    spend_data: dict[str, list[float]],
    contributions: dict[str, float],
    saturation_params: dict[str, dict],
    date_range: tuple[str, str],
) -> dict:
    """Build dict for storage in mmm_results table."""
    channels = list(spend_data.keys())
    total_budget = sum(sum(v) for v in spend_data.values()) / max(len(next(iter(spend_data.values()), [1])), 1)

    saturation_curves: dict[str, list[dict]] = {}
    for ch in channels:
        p = saturation_params.get(ch, {})
        hs = p.get("half_saturation", 5000.0)
        slope = p.get("slope", 1.0)
        roi_scale = p.get("roi_scale", 1.0)
        pts = []
        for k in range(1, 11):
            spend = hs * k * 0.5
            sat = apply_saturation([spend], hs, slope)
            marginal_roi = sat[0] * roi_scale / spend if spend > 0 else 0.0
            pts.append({"spend": round(spend, 2), "marginal_roi": round(marginal_roi, 4)})
        saturation_curves[ch] = pts

    alloc_result = optimize_budget_allocation(saturation_params, total_budget)
    n_obs = max(len(v) for v in spend_data.values()) if spend_data else 0

    return {
        "channels": channels,
        "contributions": contributions,
        "saturation_curves": saturation_curves,
        "budget_allocation": alloc_result["allocation"],
        "total_budget": round(total_budget, 2),
        "date_range_start": date_range[0],
        "date_range_end": date_range[1],
        "n_obs": n_obs,
        "fit_metrics": {"expected_roi": alloc_result["expected_roi"]},
    }
