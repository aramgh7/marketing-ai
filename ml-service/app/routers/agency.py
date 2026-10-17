from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class AgencyKpiRequest(BaseModel):
    tenant_snapshots: list[dict]
    metric:           str = "held_calls"


class CreativeSourcesRequest(BaseModel):
    source_rows: list[dict]


@router.post("/aggregate-kpis")
def aggregate_kpis(req: AgencyKpiRequest):
    from app.services.agency_service import aggregate_agency_kpis, rank_tenants_by_performance
    agg    = aggregate_agency_kpis(req.tenant_snapshots)
    ranked = rank_tenants_by_performance(req.tenant_snapshots, req.metric)
    return {"aggregate": agg, "ranked": ranked}


@router.post("/compare-sources")
def compare_sources(req: CreativeSourcesRequest):
    from app.services.agency_service import compare_creative_sources
    return compare_creative_sources(req.source_rows)
