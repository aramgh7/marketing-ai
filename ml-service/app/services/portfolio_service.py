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


# ─── Markowitz mean-variance (roadmap 9-12; vision §8.5) ─────────────────────────────────────────

def _roas_samples(asset: dict, n_samples: int, rng) -> "np.ndarray":
    """Posterior ROAS samples for one asset.

    Accepts either raw samples {roas_samples: [...]} or a Beta-scaled posterior
    {roas_alpha, roas_beta, roas_scale?}: ROAS = scale × Beta(α, β) (scale defaults to the
    tenant's max plausible ROAS, 10). Raw samples are resampled with replacement to n_samples.
    """
    import numpy as np
    samples = asset.get("roas_samples")
    if samples:
        arr = np.asarray(samples, dtype=float)
        if arr.size == 0:
            return np.zeros(n_samples)
        return rng.choice(arr, size=n_samples, replace=True) if arr.size != n_samples else arr
    a = float(asset.get("roas_alpha", 1.0))
    b = float(asset.get("roas_beta", 1.0) or 1.0)
    scale = float(asset.get("roas_scale", 10.0))
    return scale * rng.beta(max(a, 1e-6), max(b, 1e-6), size=n_samples)


def compute_markowitz(
    assets: list[dict],
    risk_aversion: float = 3.0,
    floor: float = _MIN_WEIGHT_FLOOR,
    n_samples: int = 2000,
    rng=None,
    n_iter: int = 2000,
) -> dict:
    """Long-only mean-variance allocation with a per-asset floor.

    maximise  μᵀw − λ·wᵀΣw   s.t. Σw = 1, w ≥ floor
    solved by projected gradient ascent (projection onto the floored simplex). μ and Σ come from
    posterior ROAS samples, so the covariance carries the *uncertainty* of each campaign, not only
    its historical volatility. Output is a recommendation only (never inside CBO).
    """
    import numpy as np
    g = rng if rng is not None else np.random.default_rng()
    assets = [a for a in assets if not a.get("is_cbo")]
    n = len(assets)
    if n == 0:
        return {"weights": {}, "expected_roas": 0.0, "variance": 0.0, "method": "markowitz",
                "is_recommendation_only": True, "warnings": ["no eligible assets"]}
    if n == 1:
        return {"weights": {assets[0]["id"]: 1.0}, "expected_roas": float(np.mean(_roas_samples(assets[0], n_samples, g))),
                "variance": 0.0, "method": "markowitz", "is_recommendation_only": True, "warnings": []}

    S = np.column_stack([_roas_samples(a, n_samples, g) for a in assets])   # n_samples × n
    mu = S.mean(axis=0)
    cov = np.cov(S, rowvar=False) + np.eye(n) * 1e-9
    floor = min(max(float(floor), 0.0), 1.0 / n)

    def project(w):
        """Project onto {w ≥ floor, Σw = 1}: shift by the floor, project the rest onto the simplex."""
        v = w - floor
        budget = 1.0 - floor * n
        u = np.sort(v)[::-1]
        css = np.cumsum(u)
        k = np.nonzero(u * np.arange(1, n + 1) > (css - budget))[0]
        rho = k[-1] if k.size else 0
        theta = (css[rho] - budget) / (rho + 1)
        return np.maximum(v - theta, 0.0) + floor

    w = np.full(n, 1.0 / n)
    lam = float(risk_aversion)
    step = 1.0 / (2.0 * lam * float(np.linalg.eigvalsh(cov).max()) + float(np.abs(mu).max()) + 1e-9)
    for _ in range(n_iter):
        grad = mu - 2.0 * lam * cov @ w
        w_new = project(w + step * grad)
        if np.abs(w_new - w).max() < 1e-9:
            w = w_new
            break
        w = w_new

    weights = {a["id"]: round(float(x), 4) for a, x in zip(assets, w)}
    return {
        "weights": weights,
        "expected_roas": round(float(mu @ w), 6),
        "variance": round(float(w @ cov @ w), 6),
        "mu": {a["id"]: round(float(m), 6) for a, m in zip(assets, mu)},
        "risk_aversion": lam,
        "floor": floor,
        "method": "markowitz",
        "is_recommendation_only": True,
        "warnings": [],
    }


def allocate(
    assets: list[dict],
    history_ok: bool,
    total_budget: float = 0.0,
    risk_aversion: float = 3.0,
    rng=None,
) -> dict:
    """Markowitz when ≥ 6 months of history (`history_ok`), otherwise the Sharpe allocation."""
    if history_ok:
        res = compute_markowitz(assets, risk_aversion=risk_aversion, rng=rng)
        res["allocations"] = [
            {"id": k, "weight": v, "budget": round(v * total_budget, 2)} for k, v in res["weights"].items()
        ]
        return res
    res = compute_portfolio_allocation(assets, total_budget)
    res["method"] = "sharpe"
    res["weights"] = {a["id"]: a["weight"] for a in res["allocations"]}
    return res
