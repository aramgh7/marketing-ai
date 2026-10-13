from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["Thompson Sampling"])


class DecideRequest(BaseModel):
    tenant_id: str
    experiment_id: str
    context: dict = {}


class DecideResponse(BaseModel):
    arm_id: str
    variant_id: str
    alpha: float
    beta: float
    decision_id: str


@router.post("/landing", response_model=DecideResponse)
async def decide_landing(req: DecideRequest):
    # TODO: Thompson Sampling from Redis posteriors
    return DecideResponse(
        arm_id="stub",
        variant_id="stub",
        alpha=1.0,
        beta=1.0,
        decision_id="stub-decision-id",
    )
