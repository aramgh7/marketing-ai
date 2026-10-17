from fastapi import APIRouter
from pydantic import BaseModel, Field
from typing import List, Optional
from app.services.budget_curve_service import (
    compute_budget_curve,
    find_diminishing_returns_point,
)

router = APIRouter(tags=["predict"])


class BudgetCurveRequest(BaseModel):
    alpha:               float = Field(..., gt=0)
    beta:                float = Field(..., gt=0)
    sessions_per_dollar: float = Field(..., gt=0)
    budget_points:       List[float] = Field(..., min_length=2)
    n_mc:                int   = Field(2000, ge=100, le=10000)


@router.post("/budget-curve")
def budget_curve(req: BudgetCurveRequest):
    curve = compute_budget_curve(
        req.alpha,
        req.beta,
        req.sessions_per_dollar,
        req.budget_points,
        req.n_mc,
    )
    dr_point = find_diminishing_returns_point(curve)
    return {"curve": curve, "diminishing_returns_budget": dr_point}
