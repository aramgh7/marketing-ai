from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class ArchetypeRequest(BaseModel):
    ticket_bucket:    str | None = None
    self_accredited:  bool | None = None
    time_to_form_h:   float | None = None
    material_views:   int = 0
    dataroom_opens:   int = 0
    call_outcome:     str | None = None
    objection_tags:   list[str] = []
    noshow_risk_tier: str | None = None
    touch_count:      int = 0


@router.post("/classify")
def classify_archetype_endpoint(req: ArchetypeRequest):
    from app.services.archetype_service import classify_archetype, archetype_follow_up_strategy
    result = classify_archetype(
        req.ticket_bucket, req.self_accredited, req.time_to_form_h,
        req.material_views, req.dataroom_opens, req.call_outcome,
        req.objection_tags, req.noshow_risk_tier, req.touch_count,
    )
    result["follow_up_strategy"] = archetype_follow_up_strategy(result["archetype"])
    return result
