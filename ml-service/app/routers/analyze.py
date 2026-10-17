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

_ML_BASE = os.getenv("ML_SERVICE_URL", "http://localhost:8000")
_INTERNAL_KEY = os.getenv("INTERNAL_API_KEY", "")

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
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized")


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


async def _run_analysis(job_id: str, req: AnalyzeCreativeRequest) -> None:
    """Background task: extract atoms, write to DB, update creative status."""
    await job_store.set_job(job_id, {"status": "running", "creative_id": req.creative_id})
    try:
        atoms = await creative_dna.extract(
            media_type=req.media_type,
            file_url=req.file_url,
            thumbnail_url=req.thumbnail_url,
            transcript=req.transcript,
            duration_sec=req.duration_sec,
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


# ── Form Health ───────────────────────────────────────────────────────────────

class FormHealthRequest(BaseModel):
    form_endpoint_url: str
    test_payload: Optional[dict] = None
    timeout_seconds: float = 5.0


@router.post("/form-health")
def form_health(req: FormHealthRequest):
    return check_form_health(req.form_endpoint_url, req.test_payload, req.timeout_seconds)
