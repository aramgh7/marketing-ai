from __future__ import annotations

import asyncio
import logging
import os
import tempfile
from typing import Optional

import httpx
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from app.services.media_compliance_service import run_media_compliance_checks
from app.services.face_detection_service import detect_faces_in_video
from app.config import settings

logger = logging.getLogger(__name__)
router = APIRouter()
_INTERNAL_KEY = settings.internal_api_key  # single source of truth (reads .env); router-level dependency enforces it
_MAX_DOWNLOAD = 200 * 1024 * 1024


def _auth(key: str | None) -> None:
    if _INTERNAL_KEY and key != _INTERNAL_KEY:
        raise HTTPException(status_code=403, detail="Forbidden")


class MediaCheckRequest(BaseModel):
    asset_url: str
    metadata: dict = {}
    # Roadmap 9-17: sample frames from asset_url and run face detection when the caller
    # did not supply metadata.detected_faces (needs ffmpeg; OpenCV optional — see Dockerfile INSTALL_CV)
    run_face_detection: bool = False
    frame_every_sec: float = 2.0
    max_frames: int = 12


async def _detect_from_url(url: str, every_sec: float, max_frames: int) -> dict:
    async with httpx.AsyncClient(timeout=300, follow_redirects=True) as client:
        resp = await client.get(url)
        resp.raise_for_status()
        if len(resp.content) > _MAX_DOWNLOAD:
            raise ValueError("asset larger than 200 MB")
    suffix = os.path.splitext(url.split("?")[0])[1] or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        tmp.write(resp.content)
        path = tmp.name
    try:
        return await asyncio.to_thread(detect_faces_in_video, path, every_sec, max_frames)
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


@router.post("/check")
async def check_media(
    req: MediaCheckRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _auth(x_internal_api_key)
    metadata = dict(req.metadata)
    detection = None
    if req.run_face_detection and "detected_faces" not in metadata:
        try:
            detection = await _detect_from_url(req.asset_url, req.frame_every_sec, req.max_frames)
        except Exception as exc:
            logger.warning("face detection skipped for %s: %s", req.asset_url, exc)
            detection = {"face_detection_run": False, "detected_faces": [], "reason": str(exc)}
        metadata["face_detection_run"] = detection["face_detection_run"]
        if detection["face_detection_run"]:
            metadata["detected_faces"] = detection["detected_faces"]
    result = run_media_compliance_checks(req.asset_url, metadata)
    if detection is not None:
        result["face_detection"] = detection
    return result
