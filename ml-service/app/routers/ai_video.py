from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.ai_video_service import (
    validate_provider,
    build_provider_payload,
    extract_voice_atoms,
    compute_ab_acceptance,
)

router = APIRouter()
_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class ProviderPayloadRequest(BaseModel):
    provider: str
    script_text: str
    voice_settings: dict = {}
    format_settings: dict = {}


class VoiceAtomsRequest(BaseModel):
    voice_settings: dict


class AbAcceptanceRequest(BaseModel):
    ai_conversions: int
    ai_sessions: int
    live_conversions: int
    live_sessions: int


@router.post("/provider-payload")
async def provider_payload(
    req: ProviderPayloadRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Build the provider-specific API payload for AI video generation.
    Gate: tenant must have ai_video_enabled=true (enforced in Spring Boot controller).
    """
    _auth(x_internal_api_key)

    val = validate_provider(req.provider)
    if not val["valid"]:
        raise HTTPException(status_code=400, detail=val["error"])

    return build_provider_payload(
        req.provider, req.script_text, req.voice_settings, req.format_settings
    )


@router.post("/voice-atoms")
async def voice_atoms(
    req: VoiceAtomsRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Derive VOICE_TONE / VOICE_PACE / VOICE_TIMBRE atoms from voice settings.
    These atoms enter the variation stream (4-2) for A/B testing.
    """
    _auth(x_internal_api_key)
    return {"atoms": extract_voice_atoms(req.voice_settings)}


@router.post("/ab-acceptance")
async def ab_acceptance(
    req: AbAcceptanceRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    """
    Compute P(AI >= live) via Monte Carlo Beta posteriors.
    Promotion to scale allowed only on PASS (P >= 0.9 AND >= 50 sessions per arm).
    INSUFFICIENT_DATA when either arm has < 50 sessions.
    """
    _auth(x_internal_api_key)
    return compute_ab_acceptance(
        req.ai_conversions, req.ai_sessions, req.live_conversions, req.live_sessions
    )
