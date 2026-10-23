import uuid
import logging
import os
import httpx
from fastapi import APIRouter, BackgroundTasks, Header, HTTPException
from pydantic import BaseModel
from typing import Optional

from app.models import creative_dna
from app.services import db_client, job_store
from app.services.fraud_l2_service import run_layer2_checks, compute_suspicious_share, check_night_hour_burst
from app.services.diagnostics_service import run_diagnostics, compute_lpv_click_gap
from app.services.calibration_service import compute_calibration_report
from app.services.segment_re_service import rank_atoms_by_segment
from app.services.ope_service import compute_ope, passes_ope_gate
from app.services.form_check_service import check_form_health
from app.services.noshow_attribution_service import fit_noshow_attribution
from app.services import media_atoms_service, transcription_service
from app.services import browser_form_check_service
from app.security import assert_public_http_url
from app.security import check_internal_key
from app.config import settings

_ML_BASE = os.getenv("ML_SERVICE_URL", "http://localhost:8000")
_INTERNAL_KEY = settings.internal_api_key  # ML-2: was NameError — _INTERNAL_KEY undefined

logger = logging.getLogger(__name__)
router = APIRouter(tags=["Analysis"])


# ── Fraud L2 endpoints ────────────────────────────────────────────────────────

class L2SessionData(BaseModel):
    inter_event_gaps_ms: list[float] = []
    scroll_events: list[dict] = []
    scroll_depth_pct: float = 0.0
    has_mouse_move: bool = False
    is_touch: bool = False
    ip_address: str = ""
    user_agent: str = ""
    viewport_w: int = 1920
    viewport_h: int = 1080
    recent_cluster_size: int = 0


class FraudShareRequest(BaseModel):
    total_sessions: int
    suspicious_sessions: int
    source_breakdown: dict = {}


def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


@router.post("/fraud/layer2")
async def fraud_layer2(
    req: L2SessionData,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """Run Fraud Layer 2 checks on a session (AUTOMATED_TIMING, LINEAR_SCROLL, etc.)."""
    _check_key(x_internal_api_key)
    return run_layer2_checks(req.dict())


@router.post("/fraud/share")
async def fraud_share(
    req: FraudShareRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """Compute share-of-suspicious sessions for a 24h window."""
    _check_key(x_internal_api_key)
    return compute_suspicious_share(
        req.total_sessions, req.suspicious_sessions, req.source_breakdown
    )


class DiagnosticsRequest(BaseModel):
    observations: list[dict]
    fdr: float = 0.10


class LpvGapRequest(BaseModel):
    page_views: int
    meta_clicks: int
    threshold_ratio: float = 0.5


@router.post("/diagnostics")
async def diagnostics_endpoint(
    req: DiagnosticsRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """Run diagnostics with BH correction per placement×device baseline (vision §3.2)."""
    _check_key(x_internal_api_key)
    return run_diagnostics(req.observations, req.fdr)


@router.post("/lpv-gap")
async def lpv_gap(
    req: LpvGapRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """LPV_CLICK_GAP: first-party PAGE_VIEW vs Meta clicks ratio."""
    _check_key(x_internal_api_key)
    return compute_lpv_click_gap(req.page_views, req.meta_clicks, req.threshold_ratio)


class CalibrationRequest(BaseModel):
    predictions_and_actuals: list[dict]
    n_buckets: int = 10


@router.post("/calibration")
async def calibration_endpoint(
    req: CalibrationRequest,
    x_internal_api_key: Optional[str] = Header(None, alias="X-Internal-API-Key"),
):
    """Monthly match-score calibration: ECE + per-bucket breakdown (vision §6.3a)."""
    _check_key(x_internal_api_key)
    return compute_calibration_report(req.predictions_and_actuals, req.n_buckets)


class AnalyzeCreativeRequest(BaseModel):
    tenant_id: str
    creative_id: str
    media_type: str          # "VIDEO" | "IMAGE" | "CAROUSEL"
    file_url: str | None = None
    thumbnail_url: str | None = None
    transcript: str | None = None
    duration_sec: float | None = None


class AnalyzeCreativeResponse(BaseModel):
    job_id: str
    status: str


class JobStatusResponse(BaseModel):
    job_id: str
    status: str              # queued | running | completed | failed
    atoms_count: int | None = None
    error: str | None = None


@router.post("/creative", response_model=AnalyzeCreativeResponse)
async def analyze_creative(req: AnalyzeCreativeRequest, background_tasks: BackgroundTasks):
    if req.file_url:
        assert_public_http_url(req.file_url, resolve=False)  # ML-4: SSRF guard
    job_id = str(uuid.uuid4())
    await job_store.set_job(job_id, {"status": "queued", "creative_id": req.creative_id})
    background_tasks.add_task(_run_analysis, job_id, req)
    return AnalyzeCreativeResponse(job_id=job_id, status="queued")


@router.get("/status/{job_id}", response_model=JobStatusResponse)
async def get_status(job_id: str):
    data = await job_store.get_job(job_id)
    if data is None:
        raise HTTPException(status_code=404, detail="Job not found or expired")
    return JobStatusResponse(
        job_id=job_id,
        status=data["status"],
        atoms_count=data.get("atoms_count"),
        error=data.get("error"),
    )


async def _align_atoms(tenant_id: str, atoms: list[dict]) -> None:
    """Non-blocking: align each extracted atom to the taxonomy registry."""
    headers = {"X-Internal-API-Key": _INTERNAL_KEY} if _INTERNAL_KEY else {}
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            for atom in atoms:
                atom_type = atom.get("type") or atom.get("atom_type", "")
                atom_value = atom.get("value") or atom.get("atom_value", "")
                if not atom_type or not atom_value:
                    continue
                payload = {"atom_type": atom_type, "atom_value": atom_value, "tenant_id": tenant_id}
                await client.post(f"{_ML_BASE}/taxonomy/align", json=payload, headers=headers)
    except Exception as exc:
        logger.warning("Taxonomy alignment non-critical failure: %s", exc)


def _is_image_url(url: str) -> bool:
    return any(url.lower().split("?")[0].endswith(ext) for ext in (".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif"))


async def _video_media_pass(req: "AnalyzeCreativeRequest", transcript: str | None,
                            duration: float | None) -> tuple[list[dict], str | None, float | None]:
    """Download the video once; run ffmpeg media atoms and Whisper (when no transcript was given).

    Failures are logged and skipped — vision/text extraction must still run.
    """
    import asyncio
    import os as _os
    import tempfile

    media_atoms: list[dict] = []
    try:
        data = await transcription_service.download_media(req.file_url)
    except Exception as exc:
        logger.warning("media download failed for %s: %s", req.creative_id, exc)
        return media_atoms, transcript, duration

    suffix = _os.path.splitext(req.file_url.split("?")[0])[1] or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(data)
        path = tmp.name
    try:
        try:
            media_atoms = await asyncio.to_thread(media_atoms_service.extract_media_atoms_from_file, path, duration)
            if duration is None and media_atoms_service.ffmpeg_available():
                duration, _ = await asyncio.to_thread(media_atoms_service.probe_duration_and_audio, path)
        except Exception as exc:
            logger.warning("ffmpeg media atoms failed for %s: %s", req.creative_id, exc)
        if not (transcript and transcript.strip()):
            try:
                result = await asyncio.to_thread(transcription_service.transcribe_bytes, data, suffix)
                if result.get("provider") != "stub" and result.get("text"):
                    transcript = result["text"]
                    await db_client.update_creative_transcript(req.tenant_id, req.creative_id, transcript)
            except Exception as exc:
                logger.warning("auto-transcription failed for %s: %s", req.creative_id, exc)
    finally:
        try:
            _os.unlink(path)
        except OSError:
            pass
    return media_atoms, transcript, duration


async def _run_analysis(job_id: str, req: AnalyzeCreativeRequest) -> None:
    """Background task: extract atoms, write to DB, update creative status."""
    await job_store.set_job(job_id, {"status": "running", "creative_id": req.creative_id})
    try:
        transcript = req.transcript
        media_atoms: list[dict] = []
        duration = req.duration_sec
        if req.media_type == "VIDEO" and req.file_url and not _is_image_url(req.file_url):
            # Roadmap 1-2: one download feeds ffmpeg (EDIT_PACE, MUSIC) and Whisper (auto-transcript)
            media_atoms, transcript, duration = await _video_media_pass(req, transcript, duration)

        atoms = await creative_dna.extract(
            media_type=req.media_type,
            file_url=req.file_url,
            thumbnail_url=req.thumbnail_url,
            transcript=transcript,
            duration_sec=duration,
            media_atoms=media_atoms,
        )

        if atoms:
            await db_client.save_atoms(req.tenant_id, req.creative_id, atoms)
            await _align_atoms(req.tenant_id, atoms)

        await db_client.update_creative_status(req.tenant_id, req.creative_id, "ACTIVE")

        await job_store.set_job(job_id, {
            "status": "completed",
            "creative_id": req.creative_id,
            "atoms_count": len(atoms),
        })
        logger.info("Analysis completed: creative=%s atoms=%d", req.creative_id, len(atoms))

    except Exception as exc:
        logger.exception("Analysis failed for creative %s", req.creative_id)
        await db_client.update_creative_status(req.tenant_id, req.creative_id, "UPLOADED")
        await job_store.set_job(job_id, {
            "status": "failed",
            "creative_id": req.creative_id,
            "error": str(exc),
        })


# ── Segment Random Effects ────────────────────────────────────────────────────

class SegmentEffectsRequest(BaseModel):
    atom_segment_data: list[dict]
    global_mean: float = 0.05


@router.post("/segment-effects")
def segment_effects(req: SegmentEffectsRequest):
    return {"ranked": rank_atoms_by_segment(req.atom_segment_data, req.global_mean)}


# ── OPE / IPS ─────────────────────────────────────────────────────────────────

class OpeRequest(BaseModel):
    decisions: list[dict]
    current_policy_probs: dict = {}
    w_max: float = 10.0
    min_ess: float = 200.0


@router.post("/ope/ips")
def ope_ips(req: OpeRequest):
    result = compute_ope(req.decisions, req.current_policy_probs, req.w_max, req.min_ess)
    result["passes_gate"] = passes_ope_gate(result, req.min_ess)
    return result


# ── Night-Hour Burst ──────────────────────────────────────────────────────────

class NightBurstRequest(BaseModel):
    events: list[dict]
    burst_threshold: int = 10
    window_minutes: int = 15


@router.post("/fraud/night-burst")
def night_burst(req: NightBurstRequest):
    return check_night_hour_burst(req.events, req.burst_threshold, req.window_minutes)


# ── No-show attribution (roadmap 8-3) ─────────────────────────────────────────

class NoshowAttributionRequest(BaseModel):
    tenant_id: str
    rows: list[dict]          # {atoms:{TYPE:value}, booking_distance_h, tz_known, segment, showed}
    l2: float = 1.0


@router.post("/noshow-attribution")
def noshow_attribution(req: NoshowAttributionRequest):
    """logit(show) = Σ β_j·atom_j + γ·booking_distance + δ·tz_known + segment (ridge IRLS)."""
    return fit_noshow_attribution(req.rows, l2=req.l2)


# ── Form Health ───────────────────────────────────────────────────────────────

class FormHealthRequest(BaseModel):
    form_endpoint_url: Optional[str] = None
    test_payload: Optional[dict] = None
    timeout_seconds: float = 5.0
    # roadmap 3-13: headless-browser mode
    mode: str = "http"                      # http | browser
    page_url: Optional[str] = None
    form_selector: str = "form"
    submit_selector: Optional[str] = None
    fields: Optional[dict] = None           # {css selector: value}
    success_pattern: Optional[str] = None


@router.post("/form-health")
async def form_health(req: FormHealthRequest):
    """HTTP check of the form endpoint, or (mode=browser) a real submission in headless Chromium.

    Browser mode falls back to the HTTP check when Playwright is not installed; `method` says which ran.
    """
    import asyncio
    if req.mode == "browser" and req.page_url:
        assert_public_http_url(req.page_url)
        result = await asyncio.to_thread(
            browser_form_check_service.run_browser_check, req.page_url, req.form_selector,
            req.submit_selector, req.fields, req.success_pattern, max(req.timeout_seconds, 20.0))
        if result.get("method") == "browser":
            return result
        if not req.form_endpoint_url:
            return result   # ok=None, method=unavailable — caller cannot judge
    if not req.form_endpoint_url:
        raise HTTPException(status_code=400, detail="form_endpoint_url or (mode=browser + page_url) required")
    assert_public_http_url(req.form_endpoint_url)  # audit §3.4: no POSTs to metadata/redis/postgres
    out = check_form_health(req.form_endpoint_url, req.test_payload, req.timeout_seconds)
    out["method"] = "http"
    return out
