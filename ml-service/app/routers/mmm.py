from fastapi import APIRouter
from pydantic import BaseModel

from app.services.mmm_service import (
    compute_channel_contributions,
    optimize_budget_allocation,
)

router = APIRouter(tags=["MMM"])


class ContributionsRequest(BaseModel):
    spend_data: dict[str, list[float]]
    contributions: dict[str, float] = {}


class ContributionsResponse(BaseModel):
    channels: list[str]
    contributions: dict[str, float]
    channel_contributions: dict[str, list[float]]


class SaturationParams(BaseModel):
    half_saturation: float = 5000.0
    slope: float = 1.0
    decay_rate: float = 0.5
    roi_scale: float = 1.0


class OptimizeRequest(BaseModel):
    saturation_params: dict[str, SaturationParams]
    total_budget: float


class OptimizeResponse(BaseModel):
    allocation: dict[str, float]
    expected_roi: float
    curve: list[dict]


@router.post("/contributions", response_model=ContributionsResponse)
def mmm_contributions(req: ContributionsRequest):
    channel_contribs = compute_channel_contributions(req.spend_data, req.contributions)
    return ContributionsResponse(
        channels=list(req.spend_data.keys()),
        contributions=req.contributions,
        channel_contributions=channel_contribs,
    )


@router.post("/optimize", response_model=OptimizeResponse)
def mmm_optimize(req: OptimizeRequest):
    params = {ch: p.model_dump() for ch, p in req.saturation_params.items()}
    result = optimize_budget_allocation(params, req.total_budget)
    return OptimizeResponse(**result)
