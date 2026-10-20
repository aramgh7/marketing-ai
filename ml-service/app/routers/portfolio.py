from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.portfolio_service import (
    compute_portfolio_allocation,
    check_sufficient_history,
    compute_markowitz,
    allocate,
)
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class CampaignData(BaseModel):
    id: Optional[str] = None
    name: str = ""
    is_cbo: bool = False
    daily_returns: list[float] = []


class PortfolioOptimizeRequest(BaseModel):
    campaigns: list[CampaignData]
    total_budget: float
    earliest_date: str
    latest_date: str


@router.post("/optimize")
async def optimize(
    req: PortfolioOptimizeRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Sharpe-ratio portfolio allocation across campaigns/channels.
    CBO campaigns are excluded (conflict with Meta).
    Output is ALWAYS a recommendation — marketer must confirm.
    Requires >= 6 months of history.
    """
    _auth(x_internal_api_key)

    history = check_sufficient_history(req.earliest_date, req.latest_date)
    if not history.get("sufficient"):
        raise HTTPException(
            status_code=422,
            detail=f"Insufficient history for portfolio optimisation: "
                   f"{history.get('history_months', 0):.1f} months "
                   f"(need >= {history.get('required_months', 6)}). "
                   f"Dates: {req.earliest_date} → {req.latest_date}",
        )

    campaigns = [c.model_dump() for c in req.campaigns]
    return compute_portfolio_allocation(campaigns, req.total_budget)


class MarkowitzAsset(BaseModel):
    id: str
    is_cbo: bool = False
    roas_alpha: Optional[float] = None
    roas_beta: Optional[float] = None
    roas_scale: Optional[float] = None
    roas_samples: Optional[list[float]] = None
    name: str = ""
    daily_returns: list[float] = []


class MarkowitzRequest(BaseModel):
    assets: list[MarkowitzAsset]
    total_budget: float = 0.0
    risk_aversion: float = 3.0
    earliest_date: Optional[str] = None
    latest_date: Optional[str] = None


@router.post("/markowitz")
async def markowitz(
    req: MarkowitzRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Roadmap 9-12: mean-variance allocation from posterior ROAS samples (covariance = uncertainty).
    With < 6 months history (dates given) falls back to the Sharpe allocation. Recommendation only.
    """
    _auth(x_internal_api_key)
    assets = [a.model_dump(exclude_none=True) for a in req.assets]
    history_ok = True
    if req.earliest_date and req.latest_date:
        history_ok = bool(check_sufficient_history(req.earliest_date, req.latest_date).get("sufficient"))
    if history_ok:
        res = compute_markowitz(assets, risk_aversion=req.risk_aversion)
        res["allocations"] = [{"id": k, "weight": v, "budget": round(v * req.total_budget, 2)}
                              for k, v in res["weights"].items()]
        return res
    return allocate(assets, history_ok=False, total_budget=req.total_budget)
