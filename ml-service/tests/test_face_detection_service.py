"""Roadmap 9-17: face detection on sampled frames — ffmpeg args, fallback without cv2, box merging,
media-compliance integration, has_music flag."""
import builtins

import pytest

from app.services import face_detection_service as fds
from app.services.face_detection_service import (
    boxes_to_faces,
    build_sample_frames_cmd,
    detect_faces,
)
from app.services.media_atoms_service import has_music, media_atoms
from app.services.media_compliance_service import check_music_licence, run_media_compliance_checks


# ─── ffmpeg args ─────────────────────────────────────────────────────────────

def test_sample_frames_cmd_args():
    cmd = build_sample_frames_cmd("in.mp4", "/tmp/f/frame_%03d.jpg", every_sec=2.0, max_frames=12)
    assert cmd[0] == "ffmpeg" and "-i" in cmd and cmd[cmd.index("-i") + 1] == "in.mp4"
    assert cmd[cmd.index("-vf") + 1] == "fps=0.5,scale=640:-2"
    assert cmd[cmd.index("-frames:v") + 1] == "12"
    assert cmd[-1] == "/tmp/f/frame_%03d.jpg"


def test_sample_frames_cmd_validation():
    with pytest.raises(ValueError):
        build_sample_frames_cmd("in.mp4", "x", every_sec=0)
    with pytest.raises(ValueError):
        build_sample_frames_cmd("in.mp4", "x", max_frames=0)


# ─── fallback without OpenCV ────────────────────────────────────────────────

def test_no_cv2_means_detection_not_run(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "cv2":
            raise ImportError("no cv2")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake_import)
    res = detect_faces([b"\xff\xd8\xff"], backend="opencv")
    assert res["face_detection_run"] is False and res["detected_faces"] == []
    assert "opencv" in res["reason"]


def test_backend_none():
    res = detect_faces([b"x"], backend="none")
    assert res["face_detection_run"] is False and res["frames_scanned"] == 0


# ─── box parsing with a fake detector ────────────────────────────────────────

def _fake_detector(jpeg: bytes):
    # frame content encodes the boxes: b"A" → one face, b"B" → same face + a tiny one, b"" → none
    if jpeg == b"A":
        return [(100, 100, 80, 80, 0.9)]
    if jpeg == b"B":
        return [(104, 102, 80, 80, 0.95), (5, 5, 10, 10, 0.7)]
    return []


def test_detect_faces_with_fake_detector_merges_same_face():
    res = detect_faces([b"A", b"B", b""], detector=_fake_detector)
    assert res["face_detection_run"] is True and res["frames_scanned"] == 3
    faces = res["detected_faces"]
    assert len(faces) == 1                                  # tiny box dropped, overlapping box merged
    assert faces[0]["face_id"] == "face_1" and faces[0]["frames"] == [0, 1]
    assert faces[0]["confidence"] == 0.95 and faces[0]["has_model_release"] is False


def test_boxes_to_faces_distinct_faces():
    faces = boxes_to_faces([[(0, 0, 50, 50, 0.8), (300, 300, 60, 60, 0.6)]])
    assert [f["face_id"] for f in faces] == ["face_1", "face_2"]
    assert faces[1]["bbox"] == [300, 300, 60, 60]


def test_detector_exception_counts_as_empty_frame():
    def boom(_):
        raise RuntimeError("decode failed")
    res = detect_faces([b"A"], detector=boom)
    assert res["face_detection_run"] is True and res["detected_faces"] == []


# ─── media compliance integration ───────────────────────────────────────────

def test_compliance_runs_detection_on_frames_and_fails_without_release():
    res = run_media_compliance_checks("https://x/v.mp4", {}, frames=[b"A"], detector=_fake_detector)
    assert res["checks"]["FACE"] == "FAIL"
    assert res["findings"][0]["rule"] == "NO_MODEL_RELEASE" and res["findings"][0]["face_id"] == "face_1"
    assert res["face_detection"]["face_detection_run"] is True


def test_compliance_without_backend_warns_detection_not_run():
    res = run_media_compliance_checks("https://x/v.mp4", {}, frames=[b"A"],
                                      detector=None) if fds._opencv_detector() is None else None
    if res is None:
        pytest.skip("OpenCV available on this host — fallback path not reachable")
    assert res["checks"]["FACE"] == "WARN"
    assert res["findings"][0]["rule"] == "DETECTION_NOT_RUN"


def test_compliance_keeps_caller_supplied_faces():
    meta = {"detected_faces": [{"face_id": "f1", "has_model_release": True}]}
    res = run_media_compliance_checks("https://x/v.mp4", meta, frames=[b"A"], detector=_fake_detector)
    assert res["checks"]["FACE"] == "PASS" and res["face_detection"] is None


# ─── music flag (9-17 second bullet) ─────────────────────────────────────────

def test_has_music_flag_from_audio_profile():
    assert has_music("dominant") and has_music("background") and not has_music("none") and not has_music(None)
    atoms = media_atoms(cuts=4, duration_sec=30.0, has_audio=True, mean_volume_db=-18.0, silence_share=0.05)
    music = next(a for a in atoms if a["type"] == "MUSIC")
    assert music["value"] == "dominant" and music["meta"]["has_music"] is True


def test_undeclared_music_warns_but_declared_track_passes():
    warn = check_music_licence({"has_music": True})
    assert warn["result"] == "WARN" and warn["findings"][0]["rule"] == "MUSIC_UNDECLARED"
    ok = check_music_licence({"has_music": True, "audio_tracks": [{"track_id": "t", "is_licensed": True}]})
    assert ok["result"] == "PASS"
    assert check_music_licence({})["result"] == "PASS"
