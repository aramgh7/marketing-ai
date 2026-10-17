from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os
from app.services.niche_feature_store_service import build_benchmark_record, should_publish, compute_cohens_d

router = APIRouter(prefix="/niche-store", tags=["niche-store"])
INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _check_key(key: Optional[str]):
    if INTERNAL_KEY and key != INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


class BenchmarkRequest(BaseModel):
    niche: str
    placement: str
    device_type: str
    metric: str
    niche_mean: float
    niche_std: float
    niche_n: int
    global_mean: float
    global_std: float
    global_n: int
    n_tenants: int
    source: str = "FIRST_PARTY"


@router.post("/benchmark")
async def compute_benchmark(
    req: BenchmarkRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    _check_key(x_internal_api_key)
    return build_benchmark_record(
        req.niche, req.placement, req.device_type, req.metric,
        req.niche_mean, req.niche_std, req.niche_n,
        req.global_mean, req.global_std, req.global_n,
        req.n_tenants, req.source,
    )
