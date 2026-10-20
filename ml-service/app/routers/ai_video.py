"""AI video routes (roadmap 5-6 adapter + 9-16 real provider calls).

POST /ai-video/provider-payload   build the provider request body (adapter, unchanged)
POST /ai-video/voice-atoms        VOICE_* atoms from voice settings (unchanged)
POST /ai-video/ab-acceptance      P(AI >= live) gate before scale (unchanged)
POST /ai-video/generate           submit a real generation job → {job_id, status}; polled in background
GET  /ai-video/jobs/{job_id}      job state; COMPLETED carries video_url + creative registration payload

Gate: Spring passes the tenant setting `ai_video_enabled`; false → 403. Providers without an API
key fall back to MOCK (never a silent real call). Every result carries requires_ai_label=true.
"""
from __future__ import annotations

import asyncio
import logging
import uuid
from typing import Optional

from fastapi import APIRouter, BackgroundTasks, Header, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.services import job_store
from app.services.ai_video_client import (
    COMPLETED,
    creative_registration,
    generate,
    next_poll_state,
    poll_status,
    resolve_provider,
)
from app.services.ai_video_service import (
    build_provider_payload,
    compute_ab_acceptance,
    extract_voice_atoms,
    validate_provider,
)

logger = logging.getLogger(__name__)
router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it


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


class GenerateRequest(BaseModel):
    tenant_id: str
    provider: str = "MOCK"
    script_text: str
    voice_settings: dict = {}
    format_settings: dict = {}
    title: str = "AI video"
    ai_video_enabled: bool = False      # tenants.settings.ai_video_enabled, passed by Spring


class GenerateResponse(BaseModel):
    job_id: str
    status: str
    provider: str


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


# ─── 9-16: real generation jobs ─────────────────────────────────────────────

@router.post("/generate", response_model=GenerateResponse)
async def generate_video(
    req: GenerateRequest,
    background_tasks: BackgroundTasks,
    x_internal_api_key: Optional[str] = Header(None),
):
    """Submit an AI video job. 403 unless the tenant has ai_video_enabled."""
    _auth(x_internal_api_key)
    if not req.ai_video_enabled:
        raise HTTPException(status_code=403, detail="ai_video_enabled is false for this tenant")
    try:
        provider = resolve_provider(req.provider)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))

    job_id = str(uuid.uuid4())
    try:
        result = await asyncio.to_thread(
            generate, provider, req.script_text, req.voice_settings, req.format_settings, None, None, job_id
        )
    except Exception as exc:
        logger.warning("AI video generate failed (%s): %s", provider, exc)
        raise HTTPException(status_code=502, detail=f"{provider}: {exc}")

    job = {**result, "tenant_id": req.tenant_id, "title": req.title,
           "requested_provider": req.provider.upper(), "attempts": 0,
           "voice_atoms": extract_voice_atoms(req.voice_settings)}
    if result["status"] == COMPLETED:
        job["creative"] = creative_registration(result, req.tenant_id, req.title)
    await job_store.set_job(job_id, job)
    if result["status"] != COMPLETED:
        background_tasks.add_task(_poll_job, job_id)
    return GenerateResponse(job_id=job_id, status=result["status"], provider=provider)


@router.get("/jobs/{job_id}")
async def get_job(
    job_id: str,
    x_internal_api_key: Optional[str] = Header(None),
):
    _auth(x_internal_api_key)
    data = await job_store.get_job(job_id)
    if data is None:
        raise HTTPException(status_code=404, detail="AI video job not found or expired")
    return data


async def _poll_job(job_id: str) -> None:
    """Background poller: provider status every `ai_video_poll_interval_sec` until terminal/timeout."""
    attempts = 0
    while True:
        await asyncio.sleep(settings.ai_video_poll_interval_sec)
        current = await job_store.get_job(job_id)
        if current is None:
            return
        attempts += 1
        try:
            polled = await asyncio.to_thread(
                poll_status, current["provider"], job_id, current.get("provider_job_id") or ""
            )
        except Exception as exc:
            logger.warning("AI video poll failed (%s, attempt %d): %s", job_id, attempts, exc)
            polled = {"status": current.get("status", "RUNNING"), "error": str(exc)}
        state = next_poll_state(current, polled, attempts, settings.ai_video_max_polls)
        if state["status"] == COMPLETED and state.get("video_url"):
            state["creative"] = creative_registration(state, current["tenant_id"], current.get("title", "AI video"))
        await job_store.set_job(job_id, state)
        if state.get("done"):
            return
