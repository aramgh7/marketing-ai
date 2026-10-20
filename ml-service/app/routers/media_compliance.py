from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.media_compliance_service import run_media_compliance_checks
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class MediaCheckRequest(BaseModel):
    asset_url: str
    metadata: dict = {}


@router.post("/check")
async def check_media(
    req: MediaCheckRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _auth(x_internal_api_key)
    return run_media_compliance_checks(req.asset_url, req.metadata)
