"""
Circuit Breaker L1 Bayesian check endpoint.
Called by Spring Boot CircuitBreakerService before triggering a pause.
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os

from app.services.cb_bayesian_service import l1_combined_check, compute_cpa_exceedance_probability
from app.security import check_internal_key

router = APIRouter(prefix="/cb", tags=["circuit-breaker"])


def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class L1CheckRequest(BaseModel):
    spend: float
    spend_limit: float
    clicks: int
    conversions: int
    target_cpa: float
    cpa_multiplier: float = 2.0


class CpaExceedRequest(BaseModel):
    clicks: int
    conversions: int
    total_spend: float
    target_cpa: float
    cpa_multiplier: float = 2.0
    n_samples: int = 2000


@router.post("/l1-check")
async def l1_check(
    req: L1CheckRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """
    Combined L1 circuit breaker: spend >= limit OR P(CPA > 2× target) > 0.90.
    Returns should_pause, spend_trigger, bayesian_trigger, p_cpa_exceed.
    """
    _check_key(x_internal_api_key)
    return l1_combined_check(
        req.spend,
        req.spend_limit,
        req.clicks,
        req.conversions,
        req.target_cpa,
        req.cpa_multiplier,
    )


@router.post("/cpa-exceedance")
async def cpa_exceedance(
    req: CpaExceedRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """Standalone P(CPA > k × target) computation."""
    _check_key(x_internal_api_key)
    return compute_cpa_exceedance_probability(
        req.clicks,
        req.conversions,
        req.total_spend,
        req.target_cpa,
        req.cpa_multiplier,
        req.n_samples,
    )
