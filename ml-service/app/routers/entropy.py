from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.entropy_service import build_entropy_report
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class EntropyComputeRequest(BaseModel):
    niche: str
    embeddings: list[list[float]]  # one embedding per active tenant (from atom_registry)
    tenant_count: int


@router.post("/compute")
async def compute(
    req: EntropyComputeRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Compute niche creative entropy from hook embeddings.
    Requires >= 5 tenants in the niche (returns skipped_reason otherwise).
    diversity_flag=True when entropy < 0.3 → apply diversity penalty in candidate ranking (6-4).
    """
    _auth(x_internal_api_key)

    if len(req.embeddings) != req.tenant_count:
        raise HTTPException(
            status_code=400,
            detail=f"embeddings list length ({len(req.embeddings)}) must equal tenant_count ({req.tenant_count})",
        )

    return build_entropy_report(req.niche, req.embeddings, req.tenant_count)
