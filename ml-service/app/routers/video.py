"""
Video Template Engine (vision §5.2) + Variation Generation (§5.2 §4-2).

Endpoints:
  POST /video/transcribe          — Whisper audio transcription
  POST /video/tag-scenes          — Claude LLM scene tagging from transcript
  POST /video/montage-plan        — LLM director: Hook→Problem→Solution→Proof→CTA
  POST /video/render              — FFmpeg render (async job)
  GET  /video/render/{job_id}     — render job status
  POST /video/generate-variations — Hook × Body × CTA matrix (§5.2 §4-2)

Security: all endpoints require X-Internal-API-Key header.
Marketer approval happens in Spring Boot before any creative is launched.
"""
import json
import logging
import os
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, BackgroundTasks, Header, HTTPException
from pydantic import BaseModel

from app.config import settings
from app.services import job_store
from app.services.video_service import (
    FORMAT_SCALE as _FORMAT_SCALE,
    STUB_TRANSCRIPT as _STUB_TRANSCRIPT,
    STUB_SCENES as _STUB_SCENES,
    STUB_PLAN as _STUB_PLAN,
    build_ffmpeg_cmd as _build_ffmpeg_cmd,
    build_variations as _build_variations_pure,
    parse_json_list as _parse_json_list,
)

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Video"])

# --------------------------------------------------------------------------- #
# Auth
# --------------------------------------------------------------------------- #
def _require_key(key: str) -> None:
    if key != settings.internal_api_key:
        raise HTTPException(status_code=401, detail="Invalid or missing X-Internal-API-Key")


# --------------------------------------------------------------------------- #
# 4-1: Transcribe (Whisper)
# --------------------------------------------------------------------------- #
class TranscribeRequest(BaseModel):
    footage_id: str
    tenant_id: str
    storage_url: str


class TranscribeResponse(BaseModel):
    footage_id: str
    transcript: str
    segments: list[dict]


@router.post("/transcribe", response_model=TranscribeResponse)
async def transcribe_video(
    req: TranscribeRequest,
    x_internal_api_key: str = Header(default=""),
):
    """Transcribe video audio via OpenAI Whisper API."""
    _require_key(x_internal_api_key)

    if not settings.openai_api_key:
        logger.warning("OPENAI_API_KEY not set — returning stub transcript")
        return TranscribeResponse(
            footage_id=req.footage_id,
            transcript=_STUB_TRANSCRIPT,
            segments=[{"start": 0.0, "end": 30.0, "text": _STUB_TRANSCRIPT}],
        )

    # Download the video file
    async with httpx.AsyncClient(timeout=120) as client:
        resp = await client.get(req.storage_url)
        resp.raise_for_status()
        video_bytes = resp.content

    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp.write(video_bytes)
        tmp_path = tmp.name

    try:
        import openai  # lazy import (Phase 4 only)
        oa = openai.OpenAI(api_key=settings.openai_api_key)
        with open(tmp_path, "rb") as f:
            result = oa.audio.transcriptions.create(
                model=settings.whisper_model,
                file=f,
                response_format="verbose_json",
            )
        segments = [
            {"start": s.start, "end": s.end, "text": s.text}
            for s in (result.segments or [])
        ]
        return TranscribeResponse(
            footage_id=req.footage_id,
            transcript=result.text,
            segments=segments,
        )
    finally:
        os.unlink(tmp_path)


# --------------------------------------------------------------------------- #
# 4-1: Tag Scenes (Claude LLM from transcript)
# --------------------------------------------------------------------------- #
class TagScenesRequest(BaseModel):
    footage_id: str
    tenant_id: str
    transcript: str
    duration_sec: float = 30.0


class TagScenesResponse(BaseModel):
    footage_id: str
    scenes: list[dict]


@router.post("/tag-scenes", response_model=TagScenesResponse)
async def tag_scenes(
    req: TagScenesRequest,
    x_internal_api_key: str = Header(default=""),
):
    """Use Claude to identify scene boundaries from transcript."""
    _require_key(x_internal_api_key)

    if not settings.anthropic_api_key:
        logger.warning("ANTHROPIC_API_KEY not set — returning stub scenes")
        return TagScenesResponse(footage_id=req.footage_id, scenes=_STUB_SCENES)

    import anthropic  # lazy import
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)

    prompt = f"""Analyze this video transcript and identify scene segments.
Video duration: {req.duration_sec:.1f} seconds.
Transcript: {req.transcript}

Identify 3-7 scene segments following the structure: Hook, Problem, Solution, Proof, CTA.
For each scene return JSON with keys:
- start_sec (float)
- end_sec (float)
- description (string, ≤50 chars)
- tags (list of strings from: hook, face, direct_address, problem, pain_point, solution, demo, product, proof, testimonial, cta, offer)
- recommended_for (one of: hook, problem, solution, proof, cta)

Return ONLY a JSON array. No explanation."""

    try:
        msg = client.messages.create(
            model="claude-sonnet-5-5",
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )
        raw = msg.content[0].text.strip()
        scenes = _parse_json_list(raw)
        return TagScenesResponse(footage_id=req.footage_id, scenes=scenes)
    except Exception:
        logger.exception("Scene tagging failed, returning stubs")
        return TagScenesResponse(footage_id=req.footage_id, scenes=_STUB_SCENES)


# --------------------------------------------------------------------------- #
# 4-1: Montage Plan (LLM Director)
# --------------------------------------------------------------------------- #
class MontagePlanRequest(BaseModel):
    tenant_id: str
    footage_id: str
    scenes: list[dict]
    brief_atoms: list[dict] | None = None
    target_duration_sec: int = 30
    formats: list[str] = ["9:16"]


class MontagePlanResponse(BaseModel):
    footage_id: str
    plan: list[dict]
    formats: list[str]


@router.post("/montage-plan", response_model=MontagePlanResponse)
async def create_montage_plan(
    req: MontagePlanRequest,
    x_internal_api_key: str = Header(default=""),
):
    """LLM director creates Hook→Problem→Solution→Proof→CTA montage plan."""
    _require_key(x_internal_api_key)

    if not settings.anthropic_api_key:
        logger.warning("ANTHROPIC_API_KEY not set — returning stub plan")
        return MontagePlanResponse(
            footage_id=req.footage_id,
            plan=_STUB_PLAN,
            formats=req.formats,
        )

    import anthropic
    client = anthropic.Anthropic(api_key=settings.anthropic_api_key)

    atoms_hint = ""
    if req.brief_atoms:
        atoms_hint = f"\nTop-performing atoms to emphasise: {json.dumps(req.brief_atoms[:5])}"

    prompt = f"""You are an expert video editor for performance marketing ads.
Available scenes: {json.dumps(req.scenes)}
Target duration: {req.target_duration_sec} seconds.
Formats needed: {req.formats}
{atoms_hint}

Create a montage plan as a JSON array. Each element has:
- segment: one of "hook", "problem", "solution", "proof", "cta"
- scenes: list of scene indices to include (0-indexed from available scenes)
- duration_sec: target duration for this segment (int)
- text_overlay: optional text overlay or subtitle (empty string if none)

Rules:
- Hook must be ≤3 seconds (vision §5.2: first 3 seconds decide everything)
- Total duration must not exceed {req.target_duration_sec} seconds
- Every plan must have hook, solution, and cta segments
- Return ONLY a JSON array. No explanation."""

    try:
        msg = client.messages.create(
            model="claude-sonnet-5-5",
            max_tokens=1024,
            messages=[{"role": "user", "content": prompt}],
        )
        raw = msg.content[0].text.strip()
        plan = _parse_json_list(raw)
        return MontagePlanResponse(footage_id=req.footage_id, plan=plan, formats=req.formats)
    except Exception:
        logger.exception("Montage plan failed, returning stubs")
        return MontagePlanResponse(footage_id=req.footage_id, plan=_STUB_PLAN, formats=req.formats)


# --------------------------------------------------------------------------- #
# 4-1: Render (FFmpeg async job)
# --------------------------------------------------------------------------- #
# _FORMAT_SCALE and _build_ffmpeg_cmd imported from app.services.video_service

class RenderRequest(BaseModel):
    tenant_id: str
    footage_id: str
    footage_url: str       # URL to the source footage file
    plan: list[dict]
    format: str = "9:16"
    text_overlays: dict[str, str] | None = None  # {segment: overlay_text}


class RenderResponse(BaseModel):
    job_id: str
    status: str


@router.post("/render", response_model=RenderResponse)
async def render_video(
    req: RenderRequest,
    background_tasks: BackgroundTasks,
    x_internal_api_key: str = Header(default=""),
):
    """Start async FFmpeg render job. Poll GET /video/render/{job_id} for status."""
    _require_key(x_internal_api_key)

    job_id = str(uuid.uuid4())
    await job_store.set_job(job_id, {"status": "queued", "footage_id": req.footage_id})
    background_tasks.add_task(_run_render, job_id, req)
    return RenderResponse(job_id=job_id, status="queued")


@router.get("/render/{job_id}")
async def get_render_status(
    job_id: str,
    x_internal_api_key: str = Header(default=""),
):
    _require_key(x_internal_api_key)
    data = await job_store.get_job(job_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Render job not found or expired")
    return data


async def _run_render(job_id: str, req: RenderRequest) -> None:
    await job_store.set_job(job_id, {"status": "running", "footage_id": req.footage_id})
    import time
    start = time.time()

    output_dir = Path(settings.video_output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        # Download footage
        async with httpx.AsyncClient(timeout=300) as client:
            resp = await client.get(req.footage_url)
            resp.raise_for_status()

        with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as src:
            src.write(resp.content)
            src_path = src.name

        output_path = str(output_dir / f"{job_id}.mp4")

        scale = _FORMAT_SCALE.get(req.format, "1080:1920")

        # Build FFmpeg filter: scale → pad for aspect ratio
        ffmpeg_cmd = _build_ffmpeg_cmd(src_path, output_path, req.plan, scale)

        result = subprocess.run(ffmpeg_cmd, capture_output=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError(f"FFmpeg failed: {result.stderr.decode()[:500]}")

        render_ms = int((time.time() - start) * 1000)
        file_size = os.path.getsize(output_path)

        # Upload to R2 if configured
        storage_url = output_path
        if settings.r2_account_id and settings.r2_access_key_id:
            storage_url = _upload_to_r2(output_path, req.tenant_id, job_id)

        await job_store.set_job(job_id, {
            "status": "completed",
            "footage_id": req.footage_id,
            "storage_url": storage_url,
            "render_time_ms": render_ms,
            "file_size_bytes": file_size,
            "format": req.format,
        })
        logger.info("Render %s completed in %dms → %s", job_id, render_ms, storage_url)

    except Exception as exc:
        logger.exception("Render %s failed", job_id)
        await job_store.set_job(job_id, {
            "status": "failed",
            "footage_id": req.footage_id,
            "error": str(exc)[:500],
        })
    finally:
        if "src_path" in locals() and os.path.exists(src_path):
            os.unlink(src_path)


def _upload_to_r2(local_path: str, tenant_id: str, job_id: str) -> str:
    import boto3  # lazy import
    key = f"{tenant_id}/renders/{job_id}.mp4"
    s3 = boto3.client(
        "s3",
        endpoint_url=f"https://{settings.r2_account_id}.r2.cloudflarestorage.com",
        aws_access_key_id=settings.r2_access_key_id,
        aws_secret_access_key=settings.r2_secret_access_key,
        region_name="auto",
    )
    s3.upload_file(local_path, settings.r2_bucket, key)
    base = settings.r2_public_url or f"https://{settings.r2_bucket}.{settings.r2_account_id}.r2.dev"
    return f"{base}/{key}"


# --------------------------------------------------------------------------- #
# 4-2: Variation Generation — Hook × Body × CTA matrix
# --------------------------------------------------------------------------- #
class GenerateVariationsRequest(BaseModel):
    tenant_id: str
    footage_id: str
    footage_url: str
    hooks: list[str]          # 2-3 hook scripts / overlay texts
    bodies: list[str]         # 1+ body options (usually 1)
    ctas: list[str]           # 2-3 CTA overlay texts
    formats: list[str] = ["9:16"]
    add_subtitle_variations: bool = True  # low-effort: same video + overlay styles


class VariationSpec(BaseModel):
    variation_id: str
    variation_type: str       # HOOK_VARIATION, OVERLAY_VARIATION
    hook_text: str
    body_text: str
    cta_text: str
    format: str
    render_spec: dict


class GenerateVariationsResponse(BaseModel):
    footage_id: str
    total_variations: int
    variations: list[VariationSpec]


@router.post("/generate-variations", response_model=GenerateVariationsResponse)
async def generate_variations(
    req: GenerateVariationsRequest,
    x_internal_api_key: str = Header(default=""),
):
    """
    Generate Hook × Body × CTA variation matrix (vision §5.2).

    3 hooks × 1 body × 3 CTAs = 9 HOOK_VARIATION creatives.
    Optional: +5 subtitle style overlays = OVERLAY_VARIATION (low-effort, high-variation).

    Returns render specs — rendering is async via POST /video/render.
    Marketer approval required before launch (Spring Boot /video/renders/{id}/approve).
    """
    _require_key(x_internal_api_key)

    raw_variations = _build_variations_pure(
        req.tenant_id, req.footage_id, req.footage_url,
        req.hooks, req.bodies, req.ctas,
        req.formats, req.add_subtitle_variations,
    )
    # Wrap plain dicts into Pydantic VariationSpec for the response model
    variations = [VariationSpec(**v) for v in raw_variations]
    return GenerateVariationsResponse(
        footage_id=req.footage_id,
        total_variations=len(variations),
        variations=variations,
    )
