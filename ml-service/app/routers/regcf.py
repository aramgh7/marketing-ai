from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter()


class WaitlistValueRequest(BaseModel):
    n_leads:          int
    p_convert_regcf:  float = 0.05
    avg_ticket_regcf: float = 10000.0
    confidence:       float = 0.80


@router.post("/waitlist/value")
def waitlist_value(req: WaitlistValueRequest):
    from app.services.regcf_service import estimate_waitlist_value
    return estimate_waitlist_value(
        req.n_leads, req.p_convert_regcf, req.avg_ticket_regcf, req.confidence
    )
