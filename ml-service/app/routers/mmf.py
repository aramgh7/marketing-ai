"""
Marketing Mix Funnel + Offer Explorer router (vision §10.4, §19; arch §15.12).
POST /mmf/diagnose
POST /offers/generate
POST /offers/rank
"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.mmf_service import diagnose_mmf, generate_offers, rank_offers

router = APIRouter()

_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "dev-internal-key")


def _check_key(key: Optional[str]) -> None:
    if key != _INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


class MmfDiagnoseRequest(BaseModel):
    ctr: float = 0.02
    cvr: float = 0.03
    cpc: float = 1.0
    roas: float = 2.0
    impression_share: float = 0.3
    budget_util: float = 0.5
    learning_phase: str = ""
    frequency: float = 1.0
    ctr_benchmark: float = 0.005


class GenerateOffersRequest(BaseModel):
    base_offer: dict
    context: dict = {}


class RankOffersRequest(BaseModel):
    offers: list[dict]
    scores: dict = {}


@router.post("/diagnose")
def diagnose(
    req: MmfDiagnoseRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return diagnose_mmf(req.model_dump())


@router.post("/offers/generate")
def offers_generate(
    req: GenerateOffersRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return generate_offers(req.base_offer, req.context)


@router.post("/offers/rank")
def offers_rank(
    req: RankOffersRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return rank_offers(req.offers, req.scores)
