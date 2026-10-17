from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.portfolio_service import (
    compute_portfolio_allocation,
    check_sufficient_history,
)

router = APIRouter()
_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


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
