"""
Persona Sandbox router (vision §12.6, §22; arch §15.13).
POST /persona/build
POST /persona/score
"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.persona_service import build_persona, score_session_against_persona
from app.security import check_internal_key

router = APIRouter()



def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class BuildPersonaRequest(BaseModel):
    sessions: list[dict]
    cluster_id: int
    label: str = ""


class ScoreSessionRequest(BaseModel):
    session: dict
    persona: dict


@router.post("/build")
def build(
    req: BuildPersonaRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    return build_persona(req.sessions, req.cluster_id, req.label)


@router.post("/score")
def score(
    req: ScoreSessionRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    s = score_session_against_persona(req.session, req.persona)
    return {"score": s}
