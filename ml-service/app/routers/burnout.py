from fastapi import APIRouter
from pydantic import BaseModel, Field
from typing import Optional
from app.services.burnout_service import compute_burnout_score, estimate_days_remaining
from app.services.burnout_survival_service import predict_burnout_days, should_suppress_burnout_alert

router = APIRouter(tags=["burnout"])


class BurnoutRequest(BaseModel):
    frequency:          float = Field(..., ge=0)
    ctr_current:        float = Field(..., ge=0)
    ctr_baseline:       float = Field(..., ge=0)
    spend_current:      float = Field(0.0, ge=0)
    spend_prev:         float = Field(0.0, ge=0)
    ctr_drop_rate_per_day: float = Field(0.0, ge=0)


@router.post("/score")
def burnout_score(req: BurnoutRequest):
    result = compute_burnout_score(
        req.frequency,
        req.ctr_current,
        req.ctr_baseline,
        req.spend_current,
        req.spend_prev,
    )
    if req.ctr_drop_rate_per_day > 0:
        result["days_remaining"] = estimate_days_remaining(
            req.frequency, req.ctr_drop_rate_per_day
        )
    return result


class BurnoutSurvivalRequest(BaseModel):
    frequency:            float = Field(..., ge=0)
    initial_roas:         float = Field(1.0, ge=0)
    current_days_running: int   = Field(0, ge=0)
    audience_type:        str   = "BROAD"
    season:               str   = "NORMAL"
    survival_threshold:   float = Field(0.5, ge=0.01, le=0.99)
    n_obs:                int   = Field(0, ge=0)
    anchor_verdict:       Optional[str] = None


@router.post("/survival")
def burnout_survival(req: BurnoutSurvivalRequest):
    """
    Survival model prediction (Cox PH + Weibull baseline).
    Falls back to heuristic when n_obs < 30.
    Alert suppressed when anchor_verdict = MARKET (vision §8.1).
    """
    result = predict_burnout_days(
        req.frequency,
        req.initial_roas,
        req.current_days_running,
        req.audience_type,
        req.season,
        req.survival_threshold,
        req.n_obs,
    )
    result["alert_suppressed"] = should_suppress_burnout_alert(req.anchor_verdict)
    return result
