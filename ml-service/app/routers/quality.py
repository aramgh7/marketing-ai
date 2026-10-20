from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.quality_service import (
    aggregate_quality_scores,
    compute_clickbait_check,
    compute_quality_score,
    detect_cvr_collapse,
)
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class QualityScoreRequest(BaseModel):
    match_score: Optional[float] = None
    bounced: bool
    scroll_depth: float = 0.0
    form_completion: float = 0.0
    weights: Optional[dict] = None


class ClickbaitCheckRequest(BaseModel):
    creative_ctr: float
    anchor_ctr: float
    creative_cvr: float
    anchor_cvr: float
    creative_bounce: float
    anchor_bounce: float
    ctr_n: int
    cvr_n: int
    bounce_n: int


class AggregateRequest(BaseModel):
    sessions: list[dict]
    match_score_by_variant: dict = {}
    weights: Optional[dict] = None


@router.post("/score")
async def score(req: QualityScoreRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    result = compute_quality_score(
        match_score=req.match_score,
        bounced=req.bounced,
        scroll_depth=req.scroll_depth,
        form_completion=req.form_completion,
        weights=req.weights,
    )
    return {"quality_score": result}


@router.post("/clickbait-check")
async def clickbait_check(req: ClickbaitCheckRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    return compute_clickbait_check(
        creative_ctr=req.creative_ctr,
        anchor_ctr=req.anchor_ctr,
        creative_cvr=req.creative_cvr,
        anchor_cvr=req.anchor_cvr,
        creative_bounce=req.creative_bounce,
        anchor_bounce=req.anchor_bounce,
        ctr_n=req.ctr_n,
        cvr_n=req.cvr_n,
        bounce_n=req.bounce_n,
    )


@router.post("/aggregate")
async def aggregate(req: AggregateRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    return aggregate_quality_scores(
        sessions=req.sessions,
        match_score_by_variant=req.match_score_by_variant,
        weights=req.weights,
    )


class CVRCollapseRequest(BaseModel):
    current_cvr: float
    baseline_cvr: float
    n_conversions: int
    n_sessions: int
    threshold_drop: float = 0.3
    baseline_n: Optional[int] = None   # baseline sessions (audit 6.6)


@router.post("/cvr-collapse")
async def cvr_collapse(req: CVRCollapseRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    return detect_cvr_collapse(
        current_cvr=req.current_cvr,
        baseline_cvr=req.baseline_cvr,
        n_conversions=req.n_conversions,
        n_sessions=req.n_sessions,
        threshold_drop=req.threshold_drop,
        baseline_n=req.baseline_n,
    )
