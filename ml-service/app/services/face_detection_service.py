"""Face detection on sampled video frames (roadmap 9-17; vision §12.1a) — no FastAPI imports.

  build_sample_frames_cmd(video, out_pattern, every_sec, max_frames)  pure ffmpeg argv
  sample_frames(video_path, every_sec=2.0, max_frames=12)             runs ffmpeg → list of JPEG paths
  detect_faces(frames, backend="opencv")                              → {face_detection_run, detected_faces}

Backend: OpenCV (optional dependency `opencv-python-headless`, Dockerfile ARG INSTALL_CV=1).
The Haar cascade shipped with OpenCV is used (no model download); a DNN detector can be
injected through `detector=` for tests or a better model. When cv2 cannot be imported the
result is `face_detection_run = False` and media compliance WARNs "DETECTION_NOT_RUN", exactly
as before this module existed. Faces never get `has_model_release` automatically — the
marketer must attach a release, otherwise the FACE check FAILs.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
from typing import Callable, Iterable

DEFAULT_EVERY_SEC = 2.0
DEFAULT_MAX_FRAMES = 12
MIN_FACE_PX = 40          # ignore detections smaller than this (background crowd)
DEDUP_IOU = 0.5           # same face across frames if boxes overlap this much (static shots)

Detector = Callable[[bytes], list[tuple[int, int, int, int, float]]]   # (x, y, w, h, confidence)


# ─── frame sampling ─────────────────────────────────────────────────────────

def build_sample_frames_cmd(video_path: str, out_pattern: str,
                            every_sec: float = DEFAULT_EVERY_SEC,
                            max_frames: int = DEFAULT_MAX_FRAMES) -> list[str]:
    """ffmpeg argv: one JPEG every `every_sec`, at most `max_frames`, scaled to 640 px wide."""
    if every_sec <= 0:
        raise ValueError("every_sec must be > 0")
    if max_frames <= 0:
        raise ValueError("max_frames must be > 0")
    fps = 1.0 / every_sec
    return [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", video_path,
        "-vf", f"fps={fps:g},scale=640:-2",
        "-frames:v", str(int(max_frames)),
        "-q:v", "3",
        out_pattern,
    ]


def sample_frames(video_path: str, every_sec: float = DEFAULT_EVERY_SEC,
                  max_frames: int = DEFAULT_MAX_FRAMES, out_dir: str | None = None,
                  timeout: int = 300) -> list[str]:
    """Run ffmpeg and return the written frame paths in order (empty when ffmpeg is missing)."""
    out_dir = out_dir or tempfile.mkdtemp(prefix="mai-frames-")
    pattern = os.path.join(out_dir, "frame_%03d.jpg")
    cmd = build_sample_frames_cmd(video_path, pattern, every_sec, max_frames)
    try:
        subprocess.run(cmd, capture_output=True, timeout=timeout, check=False)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return []
    frames = sorted(os.path.join(out_dir, f) for f in os.listdir(out_dir)
                    if f.startswith("frame_") and f.endswith(".jpg"))
    return frames[:max_frames]


# ─── detection ──────────────────────────────────────────────────────────────

def _opencv_detector() -> Detector | None:
    """Haar-cascade detector over JPEG bytes; None when OpenCV is unavailable/broken."""
    try:
        import cv2  # noqa: WPS433 — optional dependency
        import numpy as np
        cascade_path = os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml")
        cascade = cv2.CascadeClassifier(cascade_path)
        if cascade.empty():
            return None
    except Exception:          # ImportError, or a broken binary wheel (numpy ABI mismatch)
        return None

    def _detect(jpeg: bytes) -> list[tuple[int, int, int, int, float]]:
        arr = np.frombuffer(jpeg, dtype=np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE)
        if img is None:
            return []
        boxes = cascade.detectMultiScale(img, scaleFactor=1.1, minNeighbors=5,
                                         minSize=(MIN_FACE_PX, MIN_FACE_PX))
        return [(int(x), int(y), int(w), int(h), 0.8) for (x, y, w, h) in boxes]

    return _detect


def _iou(a: tuple[int, int, int, int], b: tuple[int, int, int, int]) -> float:
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    ix = max(0, min(ax + aw, bx + bw) - max(ax, bx))
    iy = max(0, min(ay + ah, by + bh) - max(ay, by))
    inter = ix * iy
    union = aw * ah + bw * bh - inter
    return inter / union if union > 0 else 0.0


def boxes_to_faces(per_frame: list[list[tuple[int, int, int, int, float]]],
                   min_face_px: int = MIN_FACE_PX, dedup_iou: float = DEDUP_IOU) -> list[dict]:
    """Pure: raw boxes per frame → detected_faces entries, merging the same face across frames."""
    faces: list[dict] = []
    for frame_index, boxes in enumerate(per_frame):
        for (x, y, w, h, conf) in boxes:
            if w < min_face_px or h < min_face_px:
                continue
            box = (int(x), int(y), int(w), int(h))
            merged = False
            for f in faces:
                if _iou(tuple(f["bbox"]), box) >= dedup_iou:
                    f["frames"].append(frame_index)
                    f["confidence"] = round(max(f["confidence"], float(conf)), 3)
                    merged = True
                    break
            if not merged:
                faces.append({
                    "face_id": f"face_{len(faces) + 1}",
                    "bbox": list(box),
                    "frame_index": frame_index,
                    "frames": [frame_index],
                    "confidence": round(float(conf), 3),
                    "has_model_release": False,
                })
    return faces


def _read(frame) -> bytes:
    if isinstance(frame, (bytes, bytearray)):
        return bytes(frame)
    with open(frame, "rb") as fh:
        return fh.read()


def detect_faces(frames: Iterable, backend: str = "opencv",
                 detector: Detector | None = None) -> dict:
    """Detect faces on frames (JPEG bytes or paths).

    Returns {"face_detection_run": bool, "backend": str, "frames_scanned": int,
             "detected_faces": [{face_id, bbox, frame_index, frames, confidence, has_model_release}]}
    `face_detection_run` is False when no backend is available — compliance then WARNs.
    """
    frames = list(frames)
    if backend == "none":
        return {"face_detection_run": False, "backend": "none", "frames_scanned": 0, "detected_faces": []}
    det = detector or (_opencv_detector() if backend == "opencv" else None)
    if det is None:
        return {"face_detection_run": False, "backend": backend, "frames_scanned": 0, "detected_faces": [],
                "reason": "opencv not installed (build ml-service with --build-arg INSTALL_CV=1)"}
    per_frame = []
    for frame in frames:
        try:
            per_frame.append(det(_read(frame)))
        except Exception:
            per_frame.append([])
    return {"face_detection_run": True, "backend": backend, "frames_scanned": len(per_frame),
            "detected_faces": boxes_to_faces(per_frame)}


def detect_faces_in_video(video_path: str, every_sec: float = DEFAULT_EVERY_SEC,
                          max_frames: int = DEFAULT_MAX_FRAMES, detector: Detector | None = None) -> dict:
    """sample_frames + detect_faces; cleans the temp frames up."""
    out_dir = tempfile.mkdtemp(prefix="mai-frames-")
    try:
        frames = sample_frames(video_path, every_sec, max_frames, out_dir=out_dir)
        if not frames:
            return {"face_detection_run": False, "backend": "opencv", "frames_scanned": 0,
                    "detected_faces": [], "reason": "ffmpeg produced no frames"}
        return detect_faces(frames, detector=detector)
    finally:
        for f in os.listdir(out_dir):
            try:
                os.unlink(os.path.join(out_dir, f))
            except OSError:
                pass
        try:
            os.rmdir(out_dir)
        except OSError:
            pass
