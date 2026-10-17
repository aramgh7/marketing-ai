from fastapi import APIRouter
from pydantic import BaseModel, Field
from app.services.saturation_service import (
    compute_reach_efficiency,
    classify_saturation,
    differentiate_diagnosis,
)

router = APIRouter(tags=["saturation"])


class SaturationRequest(BaseModel):
    reach_current:    float = Field(..., ge=0)
    reach_prev:       float = Field(..., ge=0)
    spend_current:    float = Field(..., gt=0)
    spend_prev:       float = Field(..., gt=0)
    frequency:        float = Field(..., ge=0)
    frequency_prev:   float = Field(..., ge=0)
    ctr_change_pct:   float = Field(0.0)


@router.post("/score")
def saturation_score(req: SaturationRequest):
    eff    = compute_reach_efficiency(
        req.reach_current, req.reach_prev, req.spend_current, req.spend_prev
    )
    result = classify_saturation(eff, req.frequency, req.frequency_prev)
    result["diagnosis"] = differentiate_diagnosis(
        req.ctr_change_pct, eff, req.frequency
    )
    return result
