from __future__ import annotations

from typing import List, Optional

from fastapi import APIRouter
from pydantic import BaseModel, Field

from app.services.cpm_service import predict_cpm

router = APIRouter(tags=["predict"])


class CpmRequest(BaseModel):
    atoms: List[str] = Field(default_factory=list)
    placement: str = "FEED"
    calendar_tags: List[str] = Field(default_factory=list)
    cpm_index: float = Field(0.0, ge=0.0)
    n_samples: int = Field(1000, ge=100, le=10000)


@router.post("/cpm")
def estimate_cpm(req: CpmRequest):
    return predict_cpm(
        atoms=req.atoms,
        placement=req.placement,
        calendar_tags=req.calendar_tags,
        cpm_index=req.cpm_index,
        n_samples=req.n_samples,
    )
