from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class PostCallRouteRequest(BaseModel):
    objection_tags: list[str]
    call_outcome:   str


class ObjectionRatesRequest(BaseModel):
    call_notes: list[dict]


class CallBatchSummaryRequest(BaseModel):
    call_notes: list[dict]
    days:       int = 30


@router.post("/route-followup")
def route_followup(req: PostCallRouteRequest):
    from app.services.post_call_service import route_followup_arm
    return route_followup_arm(req.objection_tags, req.call_outcome)


@router.post("/objection-rates")
def objection_rates(req: ObjectionRatesRequest):
    from app.services.post_call_service import compute_objection_rates
    return compute_objection_rates(req.call_notes)


@router.post("/summary")
def call_batch_summary(req: CallBatchSummaryRequest):
    from app.services.post_call_service import summarise_call_batch
    return summarise_call_batch(req.call_notes, req.days)
