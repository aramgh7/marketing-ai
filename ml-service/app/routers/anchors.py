"""
Anchor Creatives Market-Shock Detector router (vision §11.2; arch §15.2).
POST /anchors/divergence
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os

from app.services.anchors_service import compute_divergence, run_divergence_scan

router = APIRouter()

_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "dev-internal-key")


def _check_key(x_internal_api_key: Optional[str]) -> None:
    if x_internal_api_key != _INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


class ArmStat(BaseModel):
    alpha: float
    beta: float
    baseline_alpha: float
    baseline_beta: float


class DivergenceRequest(BaseModel):
    anchor_stats: list[ArmStat]
    test_stats: list[ArmStat]
    threshold: float = 0.9
    drop_threshold: float = 0.05
    metric: str = "CTR"


class MultiMetricRequest(BaseModel):
    metrics_data: dict  # {metric: {anchors: [...], tests: [...]}}
    threshold: float = 0.9


@router.post("/divergence")
def divergence(
    req: DivergenceRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    result = compute_divergence(
        anchor_stats=[s.model_dump() for s in req.anchor_stats],
        test_stats=[s.model_dump() for s in req.test_stats],
        threshold=req.threshold,
        drop_threshold=req.drop_threshold,
    )
    result["metric"] = req.metric
    return result


@router.post("/divergence/multi")
def divergence_multi(
    req: MultiMetricRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return run_divergence_scan(req.metrics_data, threshold=req.threshold)
