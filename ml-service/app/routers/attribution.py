from fastapi import APIRouter
from pydantic import BaseModel
from typing import Optional

from app.services.attribution_service import compute_mta_summary, run_all_models, aggregate_channel_credit

router = APIRouter(tags=["Attribution"])


class MtaRequest(BaseModel):
    touchpoint_paths: list[list[str]]
    method: str = "markov"


class MtaResponse(BaseModel):
    method: str
    attribution: dict[str, float]
    total_paths: int
    converting_paths: int


@router.post("/mta", response_model=MtaResponse)
def mta_attribution(req: MtaRequest):
    result = compute_mta_summary(req.touchpoint_paths, req.method)
    return MtaResponse(**result)


# ── Per-session multi-touch attribution (8-10) ────────────────────────────────

class AttributionRunRequest(BaseModel):
    touchpoints: list[dict]
    channel_weights: Optional[dict] = None
    models: list[str] = ["last_touch", "linear", "time_decay", "data_driven"]


@router.post("/run")
def run_attribution(req: AttributionRunRequest):
    all_results = run_all_models(req.touchpoints, req.channel_weights)
    filtered = {k: v for k, v in all_results.items() if k in req.models}
    channel_agg = {
        model: aggregate_channel_credit(credits, req.touchpoints)
        for model, credits in filtered.items()
    }
    return {
        "credits":       filtered,
        "by_channel":    channel_agg,
        "n_touchpoints": len(req.touchpoints),
    }


# ── Niche Benchmarks (8-9) ────────────────────────────────────────────────────

class BenchmarkComputeRequest(BaseModel):
    tenant_funnel_data: list[dict]
    data_window_days: int = 30


class TenantCompareRequest(BaseModel):
    tenant_metrics: dict
    benchmarks: list[dict]


@router.post("/benchmarks/compute")
def benchmarks_compute(req: BenchmarkComputeRequest):
    from app.services.niche_benchmark_service import compute_niche_benchmarks
    return compute_niche_benchmarks(req.tenant_funnel_data, req.data_window_days)


@router.post("/benchmarks/compare")
def benchmarks_compare(req: TenantCompareRequest):
    from app.services.niche_benchmark_service import compare_tenant_to_niche
    return compare_tenant_to_niche(req.tenant_metrics, req.benchmarks)
