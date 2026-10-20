from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os
from app.services.red_flags_service import check_red_flag
from app.security import check_internal_key

router = APIRouter(prefix="/red-flags", tags=["red-flags"])


def _check_key(key: Optional[str]):
    check_internal_key(key)


class RedFlagRequest(BaseModel):
    landing_id: str
    bounced: int
    total_visits: int
    niche_benchmark: Optional[dict] = None
    is_landing: bool = True


@router.post("/check")
async def check(
    req: RedFlagRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    _check_key(x_internal_api_key)
    return check_red_flag(
        req.landing_id, req.bounced, req.total_visits,
        req.niche_benchmark, req.is_landing,
    )
