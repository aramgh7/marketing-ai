from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.explain_service import build_explanation_context, format_explanation
from app.services.llm_client import LLMClient

router = APIRouter()
_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")

_ALLOWED_TYPES = {"variant", "creative", "campaign"}


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class ExplainRequest(BaseModel):
    stats: dict
    model: str = "claude-haiku-5-5"
    raw: bool = False  # if True, return context dict without LLM rephrasing


@router.post("/{entity_type}")
async def explain(
    entity_type: str,
    req: ExplainRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _auth(x_internal_api_key)
    if entity_type not in _ALLOWED_TYPES:
        raise HTTPException(status_code=400, detail=f"entity_type must be one of {_ALLOWED_TYPES}")

    context = build_explanation_context(entity_type, req.stats)
    if req.raw:
        return {"context": context}

    llm = LLMClient() if os.getenv("ANTHROPIC_API_KEY") else None
    text = format_explanation(entity_type, context, llm, req.model)
    return {"explanation": text, "context": context}
