"""
External Signals + Anomaly router (vision §11.3; arch §15.1).
POST /signals/anomaly
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os

from app.services.signals_service import (
    classify_anomaly,
    compute_cpm_z_score,
    route_signal_for_funnel,
    extract_narrative_context,
    summarise_weather_signals,
)
from app.security import check_internal_key

router = APIRouter()



def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class AnomalyRequest(BaseModel):
    anchor_verdict: str = "NONE"        # from /anchors/divergence
    site_health_open: bool = False
    fraud_share_pct: float = 0.0
    offer_problem: bool = False
    current_cpm: Optional[float] = None
    cpm_history: list[float] = []
    calendar_tags: list[str] = []
    fraud_threshold_pct: float = 15.0
    cpm_z_threshold: float = 2.5


@router.post("/anomaly")
def classify(
    req: AnomalyRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)

    cpm_z = None
    if req.current_cpm is not None and req.cpm_history:
        cpm_z = compute_cpm_z_score(req.current_cpm, req.cpm_history)

    return classify_anomaly(
        anchor_verdict=req.anchor_verdict,
        site_health_open=req.site_health_open,
        fraud_share_pct=req.fraud_share_pct,
        offer_problem=req.offer_problem,
        cpm_z_score=cpm_z,
        calendar_tags=req.calendar_tags,
        fraud_threshold_pct=req.fraud_threshold_pct,
        cpm_z_threshold=req.cpm_z_threshold,
    )


# ─── Funnel Signal Routing (8-10) ─────────────────────────────────────────────

class CalendarEvent(BaseModel):
    event_type:  str = ""
    event_name:  str = ""
    days_until:  int = 99


class RouteSignalRequest(BaseModel):
    funnel_key:      str
    weather_role:    str               # 'NARRATIVE' | 'DEMAND'
    raw_signals:     list[dict] = []   # rows from external_signals
    news_tags:       list[str]  = []
    calendar_events: list[CalendarEvent] = []


class NarrativeContextRequest(BaseModel):
    raw_signals:     list[dict] = []
    news_tags:       list[str]  = []
    calendar_events: list[CalendarEvent] = []


@router.post("/route")
def route_signal(
    req: RouteSignalRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    weather = summarise_weather_signals(req.raw_signals)
    events  = [e.model_dump() for e in req.calendar_events]
    return route_signal_for_funnel(
        funnel_key=req.funnel_key,
        weather_role=req.weather_role,
        weather_summary=weather,
        news_tags=req.news_tags,
        calendar_events=events,
    )


@router.post("/narrative-context")
def narrative_context(
    req: NarrativeContextRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    weather = summarise_weather_signals(req.raw_signals)
    events  = [e.model_dump() for e in req.calendar_events]
    return extract_narrative_context(
        weather_summary=weather,
        news_tags=req.news_tags,
        calendar_events=events,
    )
