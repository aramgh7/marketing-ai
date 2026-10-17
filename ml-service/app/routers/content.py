from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.content_service import (
    classify_rlha_delta,
    compute_match_score,
    generate_content,
)
from app.services.llm_client import LLMClient

router = APIRouter()
_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class GenerateRequest(BaseModel):
    tenant_id: str
    block_type: str
    slots: list[str]
    product_facts: list[str] = []
    audience: str = ""
    hook_text: str = ""
    best_atoms: list[dict] = []
    rlha_few_shots: list[dict] = []
    style_profile: str = ""
    model: str = "claude-sonnet-5-5"


class MatchScoreRequest(BaseModel):
    creative_hook: str
    landing_headline: str
    landing_offer: str = ""
    model: str = "claude-sonnet-5-5"


class RlhaDeltaRequest(BaseModel):
    generated_text: str
    approved_text: str
    model: str = "claude-haiku-5-5"


@router.post("/generate")
async def generate(req: GenerateRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    llm = LLMClient() if os.getenv("ANTHROPIC_API_KEY") else None
    return generate_content(
        block_type=req.block_type,
        slots=req.slots,
        product_facts=req.product_facts,
        audience=req.audience,
        hook_text=req.hook_text,
        best_atoms=req.best_atoms,
        rlha_few_shots=req.rlha_few_shots,
        style_profile=req.style_profile,
        llm_client=llm,
        model=req.model,
    )


@router.post("/match-score")
async def match_score(req: MatchScoreRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    llm = LLMClient() if os.getenv("ANTHROPIC_API_KEY") else None
    return compute_match_score(
        creative_hook=req.creative_hook,
        landing_headline=req.landing_headline,
        landing_offer=req.landing_offer,
        llm_client=llm,
        model=req.model,
    )


@router.post("/rlha-delta")
async def rlha_delta(req: RlhaDeltaRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    llm = LLMClient() if os.getenv("ANTHROPIC_API_KEY") else None
    tags = classify_rlha_delta(req.generated_text, req.approved_text, llm, req.model)
    return {"delta_tags": tags}
