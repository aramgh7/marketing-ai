from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os

from app.services.offer_validity_service import run_offer_validity_checks
from app.security import check_internal_key

router = APIRouter(prefix="/offer", tags=["offer-validity"])


def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class OfferValidateRequest(BaseModel):
    discount_type: str = "none"
    discount_depth: float = 0.0
    guarantee_type: str = "none"
    guarantee_days: Optional[int] = None
    has_real_urgency: bool = False
    urgency_source: Optional[str] = None
    estimated_margin: Optional[float] = None
    has_installment: bool = False
    payment_type: Optional[str] = None
    payment_terms: Optional[str] = None
    min_margin: Optional[float] = None


@router.post("/validate")
async def validate_offer(
    req: OfferValidateRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    _check_key(x_internal_api_key)
    return run_offer_validity_checks(req.dict(), req.min_margin)
