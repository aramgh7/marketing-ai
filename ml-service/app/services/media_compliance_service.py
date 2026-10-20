"""
Media Compliance service — no FastAPI/Pydantic imports.
Checks: FACE detection, MUSIC licence, AI_LABEL requirement.
Architecture §15.4, roadmap 4-5.
"""
from __future__ import annotations

from typing import Any, Callable


def check_face_detection(asset_url: str, metadata: dict) -> dict:
    """
    Check footage/render for faces without model releases.
    metadata.detected_faces: list of {face_id, has_model_release: bool}
    Returns {check_type: FACE, result: PASS|WARN|FAIL, findings: [...]}
    """
    faces: list[dict] = metadata.get("detected_faces", [])

    if not faces:
        # No face data — either no faces or detection not run → WARN
        if metadata.get("face_detection_run") is False:
            return {
                "check_type": "FACE",
                "result": "WARN",
                "findings": [{"rule": "DETECTION_NOT_RUN", "severity": "WARN",
                               "suggestion": "Run face detection before approval"}],
            }
        return {"check_type": "FACE", "result": "PASS", "findings": []}

    findings = []
    for face in faces:
        if not face.get("has_model_release"):
            findings.append({
                "rule": "NO_MODEL_RELEASE",
                "face_id": face.get("face_id", "unknown"),
                "severity": "FAIL",
                "suggestion": "Upload a signed model release for this individual",
            })

    result = "FAIL" if findings else "PASS"
    return {"check_type": "FACE", "result": result, "findings": findings}


def check_music_licence(metadata: dict) -> dict:
    """
    Check audio tracks for licence status.
    metadata.audio_tracks: list of {track_id, name, is_royalty_free: bool, is_licensed: bool}
    Returns {check_type: MUSIC, result: PASS|WARN|FAIL, findings: [...]}
    """
    tracks: list[dict] = metadata.get("audio_tracks", [])

    if not tracks:
        # Roadmap 9-17: the ffmpeg audio profile (media_atoms_service.has_music) says there is a
        # music bed, but nobody declared a track/licence for it → WARN, licence stays metadata-based
        if metadata.get("has_music") is True:
            return {
                "check_type": "MUSIC",
                "result": "WARN",
                "findings": [{"rule": "MUSIC_UNDECLARED", "severity": "WARN",
                               "suggestion": "Audio profile detected a music bed; declare the track and its licence"}],
            }
        return {"check_type": "MUSIC", "result": "PASS", "findings": []}

    findings = []
    for track in tracks:
        if not track.get("is_royalty_free") and not track.get("is_licensed"):
            findings.append({
                "rule": "UNLICENSED_TRACK",
                "track_id": track.get("track_id", "unknown"),
                "track_name": track.get("name", ""),
                "severity": "FAIL",
                "suggestion": "Replace with a royalty-free track or upload a licence certificate",
            })

    result = "FAIL" if findings else "PASS"
    return {"check_type": "MUSIC", "result": result, "findings": findings}


def check_ai_label(metadata: dict) -> dict:
    """
    Check that AI-generated content carries the required disclosure label (C2PA / §25 p.11).
    metadata.is_ai_generated: bool
    metadata.has_ai_disclosure: bool
    Returns {check_type: AI_LABEL, result: PASS|WARN|FAIL, findings: [...]}
    """
    if not metadata.get("is_ai_generated"):
        return {"check_type": "AI_LABEL", "result": "PASS", "findings": []}

    if metadata.get("has_ai_disclosure"):
        return {"check_type": "AI_LABEL", "result": "PASS", "findings": []}

    # WARN (not FAIL) — label is required but doesn't block publishing (vision §25 p.11)
    return {
        "check_type": "AI_LABEL",
        "result": "WARN",
        "findings": [{
            "rule": "MISSING_AI_DISCLOSURE",
            "severity": "WARN",
            "suggestion": "Add C2PA metadata / visible AI-generated label before publishing (§25 p.11)",
        }],
    }


def run_media_compliance_checks(asset_url: str, metadata: dict, frames: list | None = None,
                                detector: Callable | None = None) -> dict:
    """
    Run all three media checks and return combined result.
    FAIL overrides WARN overrides PASS.
    Returns {result, checks: {FACE, MUSIC, AI_LABEL}, findings: [...]}

    Roadmap 9-17: when `frames` (JPEG bytes/paths) are given and the caller did not supply
    `detected_faces`, face detection runs here and its output is merged into the metadata
    (`face_detection` key in the result shows what ran). Detected faces have no model release
    until the marketer attaches one, so a real face → FAIL, as the policy requires.
    """
    metadata = dict(metadata or {})
    detection: dict | None = None
    if frames is not None and "detected_faces" not in metadata:
        from app.services.face_detection_service import detect_faces  # local import keeps this module light
        detection = detect_faces(frames, detector=detector)
        metadata["face_detection_run"] = detection["face_detection_run"]
        if detection["face_detection_run"]:
            metadata["detected_faces"] = detection["detected_faces"]

    face = check_face_detection(asset_url, metadata)
    music = check_music_licence(metadata)
    ai_label = check_ai_label(metadata)

    all_findings = (
        [{"check_type": "FACE", **f} for f in face["findings"]]
        + [{"check_type": "MUSIC", **f} for f in music["findings"]]
        + [{"check_type": "AI_LABEL", **f} for f in ai_label["findings"]]
    )

    results = [face["result"], music["result"], ai_label["result"]]
    if "FAIL" in results:
        overall = "FAIL"
    elif "WARN" in results:
        overall = "WARN"
    else:
        overall = "PASS"

    return {
        "result": overall,
        "checks": {
            "FACE": face["result"],
            "MUSIC": music["result"],
            "AI_LABEL": ai_label["result"],
        },
        "findings": all_findings,
        "individual": [face, music, ai_label],
        "face_detection": detection,
    }
