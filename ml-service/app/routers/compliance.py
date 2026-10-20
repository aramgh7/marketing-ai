from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.compliance_service import (
    run_all_text_checks,
    run_compliance_check,
    check_meta_personal_attr,
    is_from_approved_phrases,
)
from app.services.llm_client import LLMClient
from app.config import settings

router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class ComplianceCheckRequest(BaseModel):
    text: str
    product_facts: list[str] = []
    trademark_terms: list[str] = []
    model: str = "claude-haiku-5-5"


@router.post("/check-text")
async def check_text(req: ComplianceCheckRequest, x_internal_api_key: Optional[str] = Header(None)):
    _auth(x_internal_api_key)
    llm = LLMClient() if os.getenv("ANTHROPIC_API_KEY") else None
    return run_all_text_checks(
        text=req.text,
        product_facts=req.product_facts,
        trademark_terms=req.trademark_terms,
        llm_client=llm,
        model=req.model,
    )


# ─── Securities compliance endpoints (vision §19.5) ──────────────────────────

class RulebaseCheckRequest(BaseModel):
    text: str
    rulebases: dict[str, list[dict]]  # {rulebase_key: [{rule_id, severity, pattern, ...}]}


@router.post("/check")
def check_against_rulebases(
    req: RulebaseCheckRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """Run text against one or more named rulebases (regex-based)."""
    _auth(x_internal_api_key)
    return run_compliance_check(req.text, req.rulebases)


class PersonalAttrRequest(BaseModel):
    text: str


@router.post("/check/personal-attr")
def check_personal_attr(
    req: PersonalAttrRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """Check text for Meta personal attribute targeting violations (§19.5)."""
    _auth(x_internal_api_key)
    return check_meta_personal_attr(req.text)


class ApprovedPhrasesRequest(BaseModel):
    text: str
    approved_phrases: list[str]


@router.post("/check/approved-phrases")
def check_approved_phrases(
    req: ApprovedPhrasesRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """Check whether text matches a tenant's pre-approved phrase list."""
    _auth(x_internal_api_key)
    return is_from_approved_phrases(req.text, req.approved_phrases)
