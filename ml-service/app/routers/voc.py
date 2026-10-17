"""
Voice of Customer router (vision §9.2, arch §15.8).
POST /voc/extract-objections — extract and cluster objections from raw quotes
"""
from __future__ import annotations

import logging
import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.voc_service import (
    redact_pii, build_extraction_prompt, parse_extraction_response,
    fallback_extract, CATEGORIES,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["voc"])

INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _check_key(key: Optional[str]) -> None:
    if INTERNAL_KEY and key != INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


class ExtractRequest(BaseModel):
    quotes: list[str]
    niche: Optional[str] = None
    source_type: str = "OTHER"


class ObjectionItem(BaseModel):
    category: str
    objection_text: str
    representative_quote: str
    count: int


class ExtractResponse(BaseModel):
    objections: list[ObjectionItem]
    method: str  # "llm" | "keyword"


@router.post("/extract-objections", response_model=ExtractResponse)
async def extract_objections(
    req: ExtractRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
) -> ExtractResponse:
    """Extract and cluster VoC objections from raw quotes. PII is redacted before processing."""
    _check_key(x_internal_api_key)
    if not req.quotes:
        raise HTTPException(status_code=422, detail="quotes list must not be empty")

    redacted = [redact_pii(q) for q in req.quotes]

    method = "keyword"
    items: list[dict] = []

    anthropic_key = os.getenv("ANTHROPIC_API_KEY")
    openai_key = os.getenv("OPENAI_API_KEY")

    if anthropic_key:
        try:
            import anthropic
            client = anthropic.Anthropic(api_key=anthropic_key)
            prompt = build_extraction_prompt(redacted, req.niche)
            message = client.messages.create(
                model="claude-haiku-4-5",
                max_tokens=1024,
                messages=[{"role": "user", "content": prompt}],
            )
            items = parse_extraction_response(message.content[0].text)
            if items:
                method = "llm"
        except Exception as exc:
            logger.warning("Anthropic extraction failed: %s", exc)

    if not items and openai_key:
        try:
            import openai as oai
            client = oai.OpenAI(api_key=openai_key)
            prompt = build_extraction_prompt(redacted, req.niche)
            resp = client.chat.completions.create(
                model="gpt-4o-mini",
                messages=[{"role": "user", "content": prompt}],
                max_tokens=1024,
            )
            items = parse_extraction_response(resp.choices[0].message.content or "")
            if items:
                method = "llm"
        except Exception as exc:
            logger.warning("OpenAI extraction failed: %s", exc)

    if not items:
        items = fallback_extract(redacted)
        method = "keyword"

    objections = [
        ObjectionItem(
            category=it["category"],
            objection_text=it["objection_text"],
            representative_quote=it.get("representative_quote", ""),
            count=it.get("count", 1),
        )
        for it in items
        if it.get("category") in CATEGORIES
    ]

    return ExtractResponse(objections=objections, method=method)
