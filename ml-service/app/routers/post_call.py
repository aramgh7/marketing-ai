from typing import Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.services.pii_redaction_service import redact_pii, summarise_transcript, tag_objections
from app.services.transcription_service import transcribe_url

router = APIRouter()


class PostCallRouteRequest(BaseModel):
    objection_tags: list[str]
    call_outcome:   str


class ObjectionRatesRequest(BaseModel):
    call_notes: list[dict]


class CallBatchSummaryRequest(BaseModel):
    call_notes: list[dict]
    days:       int = 30


@router.post("/route-followup")
def route_followup(req: PostCallRouteRequest):
    from app.services.post_call_service import route_followup_arm
    return route_followup_arm(req.objection_tags, req.call_outcome)


@router.post("/objection-rates")
def objection_rates(req: ObjectionRatesRequest):
    from app.services.post_call_service import compute_objection_rates
    return compute_objection_rates(req.call_notes)


@router.post("/summary")
def call_batch_summary(req: CallBatchSummaryRequest):
    from app.services.post_call_service import summarise_call_batch
    return summarise_call_batch(req.call_notes, req.days)


class TranscribeRecordingRequest(BaseModel):
    """Roadmap 8-18: call recording → Whisper → PII redaction → objection tags."""
    tenant_id: str
    recording_id: str
    recording_url: str
    language: Optional[str] = "en"


@router.post("/transcribe-recording")
async def transcribe_recording(req: TranscribeRecordingRequest):
    if not req.recording_url.startswith(("https://", "http://")):
        raise HTTPException(status_code=400, detail="recording_url must be http(s)")
    try:
        result = await transcribe_url(req.recording_url, language=req.language)
    except Exception as exc:  # download / provider failure → caller marks FAILED
        raise HTTPException(status_code=502, detail=f"transcription failed: {exc}")
    redacted, counts = redact_pii(result.get("text") or "")
    tags = tag_objections(redacted)
    return {
        "recording_id": req.recording_id,
        "transcript_redacted": redacted,     # the raw transcript is never returned or stored
        "pii_counts": counts,
        "objection_tags": tags,
        "summary": summarise_transcript(redacted),
        "provider": result.get("provider"),
        "model": result.get("model"),
        "n_segments": len(result.get("segments") or []),
    }
