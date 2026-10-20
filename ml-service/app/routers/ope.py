"""
Off-Policy Evaluation router (vision §12.5; arch §15.11).
POST /ope/evaluate
"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.ope_service import compute_ope
from app.security import check_internal_key

router = APIRouter()



def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class OpeRequest(BaseModel):
    decisions: list[dict]
    current_policy_probs: dict
    w_max: float = 10.0
    min_ess: float = 200.0
    policy_version: str = ""


@router.post("/evaluate")
def evaluate(
    req: OpeRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    result = compute_ope(
        decisions=req.decisions,
        current_policy_probs=req.current_policy_probs,
        w_max=req.w_max,
        min_ess=req.min_ess,
    )
    result["policy_version"] = req.policy_version
    return result
