from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional

from app.config import settings
from app.services.model_quality_service import (
    evaluate_thompson_quality,
    compute_rmse,
    assess_model_health,
    MIN_SAMPLE_FOR_QUALITY,
)

router = APIRouter(tags=["Model Quality"])


def _require_internal_key(x_internal_api_key: str) -> None:
    if x_internal_api_key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


class ThompsonQualityRequest(BaseModel):
    y_true: list[int]
    y_prob: list[float]


class RmseRequest(BaseModel):
    y_true: list[float]
    y_pred: list[float]


class ModelHealthRequest(BaseModel):
    model_name: str
    metrics: dict[str, float]


@router.post("/thompson")
def evaluate_thompson(
    req: ThompsonQualityRequest,
    x_internal_api_key: str = Header(default=""),
):
    """
    Evaluate Thompson Sampling arm calibration quality.
    y_true: 1=converted, 0=not. y_prob: alpha/(alpha+beta) prediction.
    Returns log-loss, Brier score, ECE and status GOOD|ACCEPTABLE|DEGRADED|INSUFFICIENT_DATA.
    """
    _require_internal_key(x_internal_api_key)
    return evaluate_thompson_quality(req.y_true, req.y_prob)


@router.post("/rmse")
def compute_model_rmse(
    req: RmseRequest,
    x_internal_api_key: str = Header(default=""),
):
    """RMSE for regression models (MMM, GLM). Returns single float."""
    _require_internal_key(x_internal_api_key)
    if len(req.y_true) < MIN_SAMPLE_FOR_QUALITY:
        raise HTTPException(
            status_code=422,
            detail=f"Minimum {MIN_SAMPLE_FOR_QUALITY} samples required, got {len(req.y_true)}",
        )
    rmse = compute_rmse(req.y_true, req.y_pred)
    return {"rmse": rmse, "n": len(req.y_true)}


@router.post("/assess")
def assess_health(
    req: ModelHealthRequest,
    x_internal_api_key: str = Header(default=""),
):
    """
    Generic health assessment for any model — returns GOOD|ACCEPTABLE|DEGRADED|UNKNOWN.
    Supported model_name values: thompson, cluster, mmm, glm, burnout.
    """
    _require_internal_key(x_internal_api_key)
    return assess_model_health(req.model_name, req.metrics)
