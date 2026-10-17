"""Reward ladder FastAPI endpoints (roadmap 8-3)."""
from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class ShowRateCorrectorRequest(BaseModel):
    booked_count: int
    held_count: int
    prior_alpha: float = 3.0
    prior_beta: float = 2.0


class GateAdjustRequest(BaseModel):
    l1_threshold: float
    l2_threshold: float
    multiplier: float


@router.post("/show-rate-corrector")
def show_rate_corrector(req: ShowRateCorrectorRequest):
    from app.services.reward_service import compute_show_rate_corrector
    return compute_show_rate_corrector(
        req.booked_count, req.held_count, req.prior_alpha, req.prior_beta
    )


@router.post("/gate-adjustment")
def gate_adjustment(req: GateAdjustRequest):
    from app.services.reward_service import apply_show_rate_to_gates
    return apply_show_rate_to_gates(req.l1_threshold, req.l2_threshold, req.multiplier)
