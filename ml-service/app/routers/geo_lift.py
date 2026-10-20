from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.geo_lift_service import (
    validate_geo_split,
    compute_synthetic_control_simple,
)
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class GeoValidateRequest(BaseModel):
    control_geos: list[str]
    test_geos: list[str]


class GeoLiftAnalyzeRequest(BaseModel):
    pre_period_control: list[float]
    pre_period_test: list[float]
    post_period_control: list[float]
    post_period_test: list[float]


@router.post("/analyze")
async def analyze(
    req: GeoLiftAnalyzeRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Synthetic-control geo-lift on client sales data.
    All lift figures carry credible intervals (vision §17.3 "honest noise").
    """
    _auth(x_internal_api_key)
    return compute_synthetic_control_simple(
        req.pre_period_control,
        req.pre_period_test,
        req.post_period_control,
        req.post_period_test,
    )


@router.post("/validate")
async def validate(
    req: GeoValidateRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """Validate geo split before creating a geo-lift test."""
    _auth(x_internal_api_key)
    return validate_geo_split(req.control_geos, req.test_geos)
