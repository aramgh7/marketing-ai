from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os
from app.services.hierarchy_prior_service import get_effective_prior
from app.security import check_internal_key

router = APIRouter(prefix="/hierarchy", tags=["hierarchy"])


def _check_key(key: Optional[str]):
    check_internal_key(key)


class PriorRequest(BaseModel):
    metric: str
    niche_benchmark: Optional[dict] = None
    client_data: Optional[dict] = None
    nu: float = 4.0


@router.post("/prior")
async def effective_prior(
    req: PriorRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    _check_key(x_internal_api_key)
    return get_effective_prior(req.metric, req.niche_benchmark, req.client_data, req.nu)
