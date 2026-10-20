"""CVR-collapse detector router (roadmap 9-8; arch §9 / §15.3).

POST /signals/cvr-collapse   (mounted by main.py with prefix="/signals")
Body: {tenant_id, entities: [{id, series: [{hour, sessions, conversions, impressions?, clicks?} × 24]}],
       min_sessions=100, traffic_tolerance=0.2, collapse_ratio=0.1}
Response: {results: [{id, verdict: COLLAPSE|OK|INSUFFICIENT_DATA, cvr_last, cvr_mean_24h, sessions_last, reason, …}]}
"""
from __future__ import annotations

from typing import Any, Optional

from fastapi import APIRouter, Header
from pydantic import BaseModel

from app.security import check_internal_key
from app.services.cvr_collapse_service import detect_many

router = APIRouter()


class CvrCollapseEntity(BaseModel):
    id: str
    series: list[dict[str, Any]] = []


class CvrCollapseRequest(BaseModel):
    tenant_id: str
    entities: list[CvrCollapseEntity] = []
    min_sessions: int = 100
    traffic_tolerance: float = 0.2
    collapse_ratio: float = 0.1


@router.post("/cvr-collapse")
def cvr_collapse(req: CvrCollapseRequest, x_internal_api_key: Optional[str] = Header(None)) -> dict:
    check_internal_key(x_internal_api_key)
    results = detect_many([e.model_dump() for e in req.entities], min_sessions=req.min_sessions,
                          traffic_tolerance=req.traffic_tolerance, collapse_ratio=req.collapse_ratio)
    return {"tenant_id": req.tenant_id, "results": results,
            "n_collapsed": sum(1 for r in results if r["verdict"] == "COLLAPSE")}
