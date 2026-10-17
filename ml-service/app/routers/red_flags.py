from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os
from app.services.red_flags_service import check_red_flag

router = APIRouter(prefix="/red-flags", tags=["red-flags"])
INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _check_key(key: Optional[str]):
    if INTERNAL_KEY and key != INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


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
