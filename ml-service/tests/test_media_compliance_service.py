"""Tests for media_compliance_service — 9 unit tests."""
import pytest
from app.services.media_compliance_service import (
    check_face_detection,
    check_music_licence,
    check_ai_label,
    run_media_compliance_checks,
)


# ── FACE tests ────────────────────────────────────────────────────────────────

def test_face_no_faces_pass():
    result = check_face_detection("s3://bucket/video.mp4", {})
    assert result["check_type"] == "FACE"
    assert result["result"] == "PASS"
    assert result["findings"] == []


def test_face_detection_not_run_warn():
    result = check_face_detection("s3://bucket/video.mp4", {"face_detection_run": False})
    assert result["result"] == "WARN"
    assert result["findings"][0]["rule"] == "DETECTION_NOT_RUN"


def test_face_with_release_pass():
    metadata = {"detected_faces": [{"face_id": "f1", "has_model_release": True}]}
    result = check_face_detection("s3://bucket/video.mp4", metadata)
    assert result["result"] == "PASS"
    assert result["findings"] == []


def test_face_without_release_fail():
    metadata = {"detected_faces": [{"face_id": "f1", "has_model_release": False}]}
    result = check_face_detection("s3://bucket/video.mp4", metadata)
    assert result["result"] == "FAIL"
    assert result["findings"][0]["rule"] == "NO_MODEL_RELEASE"
    assert result["findings"][0]["face_id"] == "f1"


# ── MUSIC tests ───────────────────────────────────────────────────────────────

def test_music_no_tracks_pass():
    result = check_music_licence({})
    assert result["check_type"] == "MUSIC"
    assert result["result"] == "PASS"


def test_music_royalty_free_pass():
    metadata = {"audio_tracks": [{"track_id": "t1", "is_royalty_free": True}]}
    result = check_music_licence(metadata)
    assert result["result"] == "PASS"


def test_music_unlicensed_fail():
    metadata = {"audio_tracks": [{"track_id": "t2", "name": "Pop Song", "is_royalty_free": False, "is_licensed": False}]}
    result = check_music_licence(metadata)
    assert result["result"] == "FAIL"
    assert result["findings"][0]["rule"] == "UNLICENSED_TRACK"


# ── AI_LABEL tests ────────────────────────────────────────────────────────────

def test_ai_label_not_ai_pass():
    result = check_ai_label({})
    assert result["check_type"] == "AI_LABEL"
    assert result["result"] == "PASS"


def test_ai_label_ai_with_disclosure_pass():
    result = check_ai_label({"is_ai_generated": True, "has_ai_disclosure": True})
    assert result["result"] == "PASS"


def test_ai_label_ai_without_disclosure_warn():
    # vision §25 p.11: AI label is required but non-blocking → WARN, not FAIL
    result = check_ai_label({"is_ai_generated": True, "has_ai_disclosure": False})
    assert result["result"] == "WARN"
    assert result["findings"][0]["rule"] == "MISSING_AI_DISCLOSURE"


# ── Combined check ────────────────────────────────────────────────────────────

def test_run_all_checks_pass():
    metadata = {
        "detected_faces": [{"face_id": "f1", "has_model_release": True}],
        "audio_tracks": [{"track_id": "t1", "is_royalty_free": True}],
        "is_ai_generated": False,
    }
    result = run_media_compliance_checks("s3://b/v.mp4", metadata)
    assert result["result"] == "PASS"
    assert result["checks"]["FACE"] == "PASS"
    assert result["checks"]["MUSIC"] == "PASS"
    assert result["checks"]["AI_LABEL"] == "PASS"


def test_run_all_checks_fail_propagates():
    metadata = {
        "detected_faces": [{"face_id": "f2", "has_model_release": False}],
        "audio_tracks": [],
        "is_ai_generated": False,
    }
    result = run_media_compliance_checks("s3://b/v.mp4", metadata)
    assert result["result"] == "FAIL"
    assert result["checks"]["FACE"] == "FAIL"
