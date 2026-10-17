"""
Portfolio Budget Optimisation service — no FastAPI/Pydantic imports.
Sharpe-ratio mean-variance allocation across campaigns/channels.
NEVER inside a CBO campaign (conflict with Meta).
Output = recommendation ONLY, not action (vision §8.5).
Architecture §15.21, roadmap 6-7.
"""
from __future__ import annotations

import math
from datetime import date, timedelta
from typing import Optional

_MIN_HISTORY_MONTHS = 6
_MIN_WEIGHT_FLOOR = 0.05     # 5% floor per asset
_RISK_FREE_RATE   = 0.0      # daily risk-free return
_ANNUALISE        = 365.0


def check_sufficient_history(
    earliest_date_str: str,
    latest_date_str: str,
) -> dict:
    """
    Verify at least 6 months of ad-set return history.
    Dates as ISO strings (YYYY-MM-DD).
    """
    try:
        d0 = date.fromisoformat(earliest_date_str)
        d1 = date.fromisoformat(latest_date_str)
    except ValueError as exc:
        return {"sufficient": False, "error": str(exc)}

    days = (d1 - d0).days
    months = days / 30.44  # average month length
    required_days = _MIN_HISTORY_MONTHS * 30.44

    return {
        "sufficient": days >= required_days,
        "history_days": days,
        "history_months": round(months, 1),
        "required_months": _MIN_HISTORY_MONTHS,
    }


def _sharpe_weight(returns: list[float], risk_free: float = _RISK_FREE_RATE) -> float:
    """
    Annualised Sharpe ratio for a single asset.
    """
    if len(returns) < 2:
        return 0.0
    import math as _math
    n = len(returns)
    mean_r = sum(returns) / n
    variance = sum((r - mean_r) ** 2 for r in returns) / (n - 1)
    std_r = _math.sqrt(variance) if variance > 0 else 1e-9
    # Annualise: daily mean * 365 / (daily std * sqrt(365))
    sharpe = (mean_r - risk_free) * _ANNUALISE / (std_r * math.sqrt(_ANNUALISE))
    return max(sharpe, 0.0)  # floor at 0 — negative Sharpe gets zero allocation


def compute_portfolio_allocation(
    campaigns: list[dict],
    total_budget: float,
) -> dict:
    """
    Compute Sharpe-ratio portfolio allocation.
    Each campaign dict: {id, name, is_cbo: bool, daily_returns: list[float]}.
    CBO campaigns excluded (conflict with Meta's own optimisation).
    5% minimum weight floor applied to each non-CBO campaign.
    Output is recommendation only — caller must confirm before any spend change.

    Returns {allocations: [{id, name, weight, budget}], total_budget, is_recommendation_only,
             excluded_cbo: [...], warnings: [...]}
    """
    warnings = []
    excluded_cbo = []
    eligible = []

    for c in campaigns:
        if c.get("is_cbo"):
            excluded_cbo.append(c.get("id") or c.get("name", "unknown"))
            warnings.append(f"CBO campaign '{c.get('name', c.get('id'))}' excluded "
                            "(Portfolio allocation conflicts with Meta CBO — §8.5)")
        else:
            eligible.append(c)

    if not eligible:
        return {
            "allocations": [],
            "total_budget": total_budget,
            "is_recommendation_only": True,
            "excluded_cbo": excluded_cbo,
            "warnings": warnings + ["No eligible campaigns after CBO exclusion"],
        }

    # Compute Sharpe per campaign
    sharpes = []
    for c in eligible:
        returns = c.get("daily_returns") or []
        sharpes.append(_sharpe_weight(returns))

    total_sharpe = sum(sharpes)

    # If all Sharpe are zero, use equal weights
    if total_sharpe == 0:
        raw_weights = [1.0 / len(eligible)] * len(eligible)
        warnings.append("All campaigns have zero or negative Sharpe; using equal weights")
    else:
        raw_weights = [s / total_sharpe for s in sharpes]

    # Apply 5% floor and renormalize
    weights = _apply_floor(raw_weights, _MIN_WEIGHT_FLOOR)

    allocations = []
    for c, w in zip(eligible, weights):
        allocations.append({
            "id":     c.get("id"),
            "name":   c.get("name", ""),
            "weight": round(w, 4),
            "budget": round(w * total_budget, 2),
            "sharpe": round(_sharpe_weight(c.get("daily_returns") or []), 3),
        })

    # Sort by budget descending
    allocations.sort(key=lambda x: x["budget"], reverse=True)

    return {
        "allocations":          allocations,
        "total_budget":         total_budget,
        "is_recommendation_only": True,  # never an action — must be confirmed by marketer
        "excluded_cbo":         excluded_cbo,
        "warnings":             warnings,
        "note":                 "Output is recommendation only. Requires ≥6 months history per §8.5.",
    }


def _apply_floor(weights: list[float], floor: float) -> list[float]:
    """Iteratively apply minimum weight floor, renormalising remaining budget."""
    w = list(weights)
    n = len(w)
    if n == 0:
        return w

    max_iter = n * 2
    for _ in range(max_iter):
        clamped = [max(x, floor) for x in w]
        total = sum(clamped)
        if total == 0:
            return [1.0 / n] * n
        w = [c / total for c in clamped]
        if all(x >= floor - 1e-9 for x in w):
            break

    return w
