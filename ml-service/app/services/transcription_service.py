"""Audio/video transcription helper (roadmap 8-18 call recordings; 1-2 auto-transcription on upload).

Downloads a media file, sends it to OpenAI Whisper, returns {text, segments, provider}.
Without OPENAI_API_KEY it returns a deterministic stub so pipelines stay testable.
Network/API calls are isolated here so routers and tests never touch them directly.
"""
from __future__ import annotations

import logging
import os
import tempfile

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

STUB_TRANSCRIPT = "This is a stub transcript. Whisper is not configured (OPENAI_API_KEY empty)."
MAX_BYTES = 200 * 1024 * 1024


async def download_media(url: str, timeout: float = 180.0) -> bytes:
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        if len(resp.content) > MAX_BYTES:
            raise ValueError(f"media larger than {MAX_BYTES // (1024 * 1024)} MB")
        return resp.content


def transcribe_bytes(media: bytes, suffix: str = ".mp4", language: str | None = None) -> dict:
    """Synchronous Whisper call; returns {text, segments, provider, model}."""
    if not settings.openai_api_key:
        logger.warning("OPENAI_API_KEY not set — stub transcript")
        return {"text": STUB_TRANSCRIPT, "segments": [{"start": 0.0, "end": 30.0, "text": STUB_TRANSCRIPT}],
                "provider": "stub", "model": None}

    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(media)
        path = tmp.name
    try:
        import openai  # lazy
        client = openai.OpenAI(api_key=settings.openai_api_key)
        with open(path, "rb") as f:
            kwargs = {"model": settings.whisper_model, "file": f, "response_format": "verbose_json"}
            if language:
                kwargs["language"] = language
            result = client.audio.transcriptions.create(**kwargs)
        segments = [{"start": s.start, "end": s.end, "text": s.text} for s in (getattr(result, "segments", None) or [])]
        return {"text": result.text, "segments": segments, "provider": "openai", "model": settings.whisper_model}
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


async def transcribe_url(url: str, language: str | None = None) -> dict:
    suffix = os.path.splitext(url.split("?")[0])[1] or ".mp4"
    if suffix.lower() not in (".mp3", ".mp4", ".m4a", ".wav", ".webm", ".ogg", ".mpeg", ".mpga", ".flac"):
        suffix = ".mp4"
    media = await download_media(url)
    return transcribe_bytes(media, suffix=suffix, language=language)
