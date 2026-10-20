"""AI video provider client (roadmap 9-16; vision §5.2) — no FastAPI/Pydantic imports.

Real HTTP adapters for HeyGen v2, Synthesia v2 and Runway Gen-3, plus a MOCK provider that
completes immediately. Every adapter is split into

  build_<provider>_generate_request(payload, api_key) -> {method, url, headers, json}
  build_<provider>_status_request(job_ref, api_key)   -> {method, url, headers}
  parse_<provider>_generate_response(json)            -> {provider_job_id}
  parse_<provider>_status_response(json)              -> {status, video_url, error}

so tests cover the request/response shapes without the network. `generate()` / `poll_status()`
take an optional `httpx.Client` (inject `httpx.MockTransport` in tests).

Statuses are normalised to QUEUED | RUNNING | COMPLETED | FAILED. Every result carries
`requires_ai_label = True` (§25 p.11 — AI-generated content must be disclosed); the A/B
acceptance rule in `ai_video_service.compute_ab_acceptance` stays mandatory before scale.

Keys: settings.heygen_api_key / synthesia_api_key / runway_api_key. Without a key the provider
is not configured → `provider_configured(...)` is False and callers fall back to MOCK.
"""
from __future__ import annotations

import uuid
from typing import Any, Optional

import httpx

from app.services.ai_video_service import build_provider_payload, validate_provider

HEYGEN_BASE = "https://api.heygen.com"
SYNTHESIA_BASE = "https://api.synthesia.io"
RUNWAY_BASE = "https://api.dev.runwayml.com"
RUNWAY_VERSION = "2024-11-06"

QUEUED, RUNNING, COMPLETED, FAILED = "QUEUED", "RUNNING", "COMPLETED", "FAILED"
TERMINAL = {COMPLETED, FAILED}

_HEYGEN_STATUS = {"pending": QUEUED, "waiting": QUEUED, "processing": RUNNING,
                  "completed": COMPLETED, "failed": FAILED}
_SYNTHESIA_STATUS = {"queued": QUEUED, "in_progress": RUNNING, "processing": RUNNING,
                     "complete": COMPLETED, "completed": COMPLETED, "failed": FAILED, "rejected": FAILED}
_RUNWAY_STATUS = {"PENDING": QUEUED, "THROTTLED": QUEUED, "RUNNING": RUNNING,
                  "SUCCEEDED": COMPLETED, "FAILED": FAILED, "CANCELLED": FAILED}

MOCK_VIDEO_URL = "https://mock.local/ai-video/{job}.mp4"


# ─── key handling ───────────────────────────────────────────────────────────

def api_key_for(provider: str, keys: dict[str, str] | None = None) -> str:
    """Resolve the API key for a provider from an explicit dict or from settings."""
    p = provider.upper()
    if keys is not None:
        return (keys.get(p) or keys.get(p.lower()) or "").strip()
    try:
        from app.config import settings  # lazy: tests must not need pydantic-settings
        return {
            "HEYGEN": getattr(settings, "heygen_api_key", ""),
            "SYNTHESIA": getattr(settings, "synthesia_api_key", ""),
            "RUNWAY": getattr(settings, "runway_api_key", ""),
        }.get(p, "").strip()
    except Exception:  # pragma: no cover
        return ""


def provider_configured(provider: str, keys: dict[str, str] | None = None) -> bool:
    p = provider.upper()
    if p == "MOCK":
        return True
    return bool(api_key_for(p, keys))


def resolve_provider(requested: str, keys: dict[str, str] | None = None) -> str:
    """Requested provider if its key is present, else MOCK (never a silent real call)."""
    val = validate_provider(requested)
    if not val["valid"]:
        raise ValueError(val["error"])
    p = val["provider"]
    return p if provider_configured(p, keys) else "MOCK"


# ─── request builders (pure) ────────────────────────────────────────────────

def build_heygen_generate_request(payload: dict, api_key: str) -> dict:
    return {"method": "POST", "url": f"{HEYGEN_BASE}/v2/video/generate",
            "headers": {"X-Api-Key": api_key, "Content-Type": "application/json"},
            "json": payload}


def build_heygen_status_request(job_ref: str, api_key: str) -> dict:
    return {"method": "GET", "url": f"{HEYGEN_BASE}/v1/video_status.get",
            "params": {"video_id": job_ref},
            "headers": {"X-Api-Key": api_key}}


def build_synthesia_generate_request(payload: dict, api_key: str) -> dict:
    return {"method": "POST", "url": f"{SYNTHESIA_BASE}/v2/videos",
            "headers": {"Authorization": api_key, "Content-Type": "application/json"},
            "json": payload}


def build_synthesia_status_request(job_ref: str, api_key: str) -> dict:
    return {"method": "GET", "url": f"{SYNTHESIA_BASE}/v2/videos/{job_ref}",
            "headers": {"Authorization": api_key}}


def build_runway_generate_request(payload: dict, api_key: str) -> dict:
    # image_to_video needs promptImage; a pure text prompt uses the same endpoint with promptText only
    return {"method": "POST", "url": f"{RUNWAY_BASE}/v1/image_to_video",
            "headers": {"Authorization": f"Bearer {api_key}", "X-Runway-Version": RUNWAY_VERSION,
                        "Content-Type": "application/json"},
            "json": payload}


def build_runway_status_request(job_ref: str, api_key: str) -> dict:
    return {"method": "GET", "url": f"{RUNWAY_BASE}/v1/tasks/{job_ref}",
            "headers": {"Authorization": f"Bearer {api_key}", "X-Runway-Version": RUNWAY_VERSION}}


_GENERATE_BUILDERS = {"HEYGEN": build_heygen_generate_request,
                      "SYNTHESIA": build_synthesia_generate_request,
                      "RUNWAY": build_runway_generate_request}
_STATUS_BUILDERS = {"HEYGEN": build_heygen_status_request,
                    "SYNTHESIA": build_synthesia_status_request,
                    "RUNWAY": build_runway_status_request}


def build_generate_request(provider: str, payload: dict, api_key: str) -> dict:
    return _GENERATE_BUILDERS[provider.upper()](payload, api_key)


def build_status_request(provider: str, job_ref: str, api_key: str) -> dict:
    return _STATUS_BUILDERS[provider.upper()](job_ref, api_key)


# ─── response parsers (pure) ────────────────────────────────────────────────

def parse_generate_response(provider: str, body: dict) -> dict:
    """Extract the provider job reference from a generate response."""
    p = provider.upper()
    if p == "HEYGEN":
        ref = (body.get("data") or {}).get("video_id")
        err = body.get("error")
    elif p == "SYNTHESIA":
        ref = body.get("id")
        err = body.get("message") if not ref else None
    elif p == "RUNWAY":
        ref = body.get("id")
        err = body.get("error") if not ref else None
    else:
        ref, err = body.get("id"), None
    if not ref:
        raise ValueError(f"{p}: no job id in response ({err or body})")
    return {"provider_job_id": str(ref)}


def parse_status_response(provider: str, body: dict) -> dict:
    """Normalise a status response → {status, video_url, error, raw_status}."""
    p = provider.upper()
    if p == "HEYGEN":
        data = body.get("data") or {}
        raw = str(data.get("status", "")).lower()
        status = _HEYGEN_STATUS.get(raw, RUNNING)
        url = data.get("video_url")
        err = (data.get("error") or {}).get("message") if isinstance(data.get("error"), dict) else data.get("error")
    elif p == "SYNTHESIA":
        raw = str(body.get("status", "")).lower()
        status = _SYNTHESIA_STATUS.get(raw, RUNNING)
        url = body.get("download")
        err = body.get("message") if status == FAILED else None
    elif p == "RUNWAY":
        raw = str(body.get("status", "")).upper()
        status = _RUNWAY_STATUS.get(raw, RUNNING)
        out = body.get("output") or []
        url = out[0] if isinstance(out, list) and out else None
        err = body.get("failure") or body.get("failureCode") if status == FAILED else None
    else:
        raw = str(body.get("status", COMPLETED))
        status = raw.upper() if raw.upper() in TERMINAL | {QUEUED, RUNNING} else COMPLETED
        url, err = body.get("video_url"), body.get("error")
    if status == COMPLETED and not url:
        status = RUNNING  # provider said done but the file is not ready yet
    return {"status": status, "video_url": url, "error": err, "raw_status": raw}


# ─── result envelope ────────────────────────────────────────────────────────

def make_result(provider: str, job_id: str, status: str, video_url: str | None = None,
                provider_job_id: str | None = None, error: str | None = None) -> dict:
    """Every result carries the AI label requirement — it cannot be switched off."""
    return {
        "job_id": job_id,
        "provider": provider.upper(),
        "provider_job_id": provider_job_id,
        "status": status,
        "video_url": video_url,
        "error": error,
        "requires_ai_label": True,
        "is_ai_generated": True,
        "ab_acceptance_required": True,
    }


# ─── I/O ────────────────────────────────────────────────────────────────────

def _send(client: httpx.Client, req: dict) -> dict:
    resp = client.request(req["method"], req["url"], headers=req.get("headers"),
                          json=req.get("json"), params=req.get("params"), timeout=60)
    if resp.status_code >= 400:
        raise RuntimeError(f"{req['method']} {req['url']} → HTTP {resp.status_code}: {resp.text[:300]}")
    return resp.json() if resp.content else {}


def generate(provider: str, script_text: str, voice_settings: dict, format_settings: dict,
             api_key: str | None = None, client: Optional[httpx.Client] = None,
             job_id: str | None = None) -> dict:
    """Submit a generation request. MOCK completes at once; real providers return QUEUED."""
    p = provider.upper()
    job_id = job_id or str(uuid.uuid4())
    built = build_provider_payload(p, script_text, voice_settings, format_settings)
    if p == "MOCK":
        return make_result(p, job_id, COMPLETED, MOCK_VIDEO_URL.format(job=job_id), f"mock-{job_id}")
    key = api_key if api_key is not None else api_key_for(p)
    if not key:
        raise ValueError(f"{p}: API key not configured")
    req = build_generate_request(p, built["payload"], key)
    own = client is None
    client = client or httpx.Client()
    try:
        body = _send(client, req)
    finally:
        if own:
            client.close()
    ref = parse_generate_response(p, body)["provider_job_id"]
    return make_result(p, job_id, QUEUED, None, ref)


def poll_status(provider: str, job_id: str, provider_job_id: str, api_key: str | None = None,
                client: Optional[httpx.Client] = None) -> dict:
    """One status check → normalised result."""
    p = provider.upper()
    if p == "MOCK":
        return make_result(p, job_id, COMPLETED, MOCK_VIDEO_URL.format(job=job_id), provider_job_id)
    key = api_key if api_key is not None else api_key_for(p)
    req = build_status_request(p, provider_job_id, key)
    own = client is None
    client = client or httpx.Client()
    try:
        body = _send(client, req)
    finally:
        if own:
            client.close()
    parsed = parse_status_response(p, body)
    return make_result(p, job_id, parsed["status"], parsed["video_url"], provider_job_id, parsed["error"])


def next_poll_state(current: dict, polled: dict, attempts: int, max_attempts: int) -> dict:
    """Pure state machine step for the background poller.

    Returns the job dict to persist; `done` tells the poller to stop.
    """
    status = polled["status"]
    if status in TERMINAL:
        return {**current, **polled, "attempts": attempts, "done": True}
    if attempts >= max_attempts:
        return {**current, "status": FAILED, "error": f"timeout after {attempts} polls",
                "attempts": attempts, "done": True, "requires_ai_label": True}
    return {**current, **polled, "attempts": attempts, "done": False}


def creative_registration(result: dict, tenant_id: str, title: str) -> dict:
    """Payload Spring uses to register the finished video as a creative with the AI label."""
    if result.get("status") != COMPLETED or not result.get("video_url"):
        raise ValueError("creative registration needs a COMPLETED result with a video_url")
    return {
        "tenant_id": tenant_id,
        "type": "VIDEO",
        "title": title,
        "file_url": result["video_url"],
        "is_ai_generated": True,
        "has_ai_disclosure": False,   # the marketer must add the label; media compliance WARNs until then
        "ai_provider": result["provider"],
        "ai_job_id": result["job_id"],
        "requires_ai_label": True,
        "ab_acceptance_required": True,
    }
