"""EDIT_PACE and MUSIC atoms from the media file itself (roadmap 1-2; vision §4.2).

Pure parsing/labelling functions (unit-tested) plus thin ffmpeg/ffprobe runners (subprocess,
not tested here). ffmpeg ships in the ml-service image.

  EDIT_PACE  = scene cuts per minute, from ffmpeg `select='gt(scene,THRESH)',showinfo`
               static (< 2) | slow (2–8) | medium (8–20) | fast (> 20)
  MUSIC      = presence of an audio bed, from ffprobe (audio stream) + volumedetect + silencedetect
               none | background | dominant
"""
from __future__ import annotations

import re
import shutil
import subprocess

SCENE_THRESHOLD = 0.40
SILENCE_DB = -30.0
SILENCE_MIN_SEC = 0.5

_PTS = re.compile(r"pts_time:\s*([0-9]+(?:\.[0-9]+)?)")
_MEAN_VOL = re.compile(r"mean_volume:\s*(-?[0-9]+(?:\.[0-9]+)?)\s*dB")
_MAX_VOL = re.compile(r"max_volume:\s*(-?[0-9]+(?:\.[0-9]+)?)\s*dB")
_SIL_START = re.compile(r"silence_start:\s*([0-9]+(?:\.[0-9]+)?)")
_SIL_END = re.compile(r"silence_end:\s*([0-9]+(?:\.[0-9]+)?)")


# ─── pure ───────────────────────────────────────────────────────────────────

def parse_scene_timestamps(showinfo_output: str) -> list[float]:
    """Timestamps (s) of frames that passed the scene filter, from ffmpeg's showinfo lines."""
    return [float(m.group(1)) for m in _PTS.finditer(showinfo_output or "")]


def edit_pace_label(cuts: int, duration_sec: float | None) -> str | None:
    if not duration_sec or duration_sec <= 0:
        return None
    cpm = cuts / (duration_sec / 60.0)
    if cpm < 2:
        return "static"
    if cpm < 8:
        return "slow"
    if cpm < 20:
        return "medium"
    return "fast"


def cuts_per_minute(cuts: int, duration_sec: float | None) -> float | None:
    if not duration_sec or duration_sec <= 0:
        return None
    return round(cuts / (duration_sec / 60.0), 2)


def parse_volume(output: str) -> tuple[float | None, float | None]:
    """(mean_volume_db, max_volume_db) from ffmpeg volumedetect output."""
    m1 = _MEAN_VOL.search(output or "")
    m2 = _MAX_VOL.search(output or "")
    return (float(m1.group(1)) if m1 else None, float(m2.group(1)) if m2 else None)


def parse_silence_share(output: str, duration_sec: float | None) -> float | None:
    """Share of the track that is silent, from ffmpeg silencedetect output."""
    if not duration_sec or duration_sec <= 0:
        return None
    starts = [float(m.group(1)) for m in _SIL_START.finditer(output or "")]
    ends = [float(m.group(1)) for m in _SIL_END.finditer(output or "")]
    silent = 0.0
    for i, s in enumerate(starts):
        e = ends[i] if i < len(ends) else duration_sec
        silent += max(0.0, min(e, duration_sec) - s)
    return round(min(1.0, silent / duration_sec), 4)


def music_label(has_audio: bool, mean_volume_db: float | None, silence_share: float | None) -> str:
    """none | background | dominant.

    No audio stream, or a track that is essentially silent → none.
    A continuous bed (little silence) at a healthy level → dominant.
    Otherwise (speech with gaps, quiet bed) → background.
    """
    if not has_audio:
        return "none"
    if mean_volume_db is not None and mean_volume_db < -45.0:
        return "none"
    if silence_share is None:
        return "background"
    if silence_share >= 0.6:
        return "none"
    if silence_share <= 0.10 and (mean_volume_db is None or mean_volume_db > -25.0):
        return "dominant"
    return "background"


def has_music(label: str | None) -> bool:
    """Roadmap 9-17: a music bed is present when the MUSIC atom is background or dominant."""
    return label in ("background", "dominant")


def media_atoms(cuts: int, duration_sec: float | None, has_audio: bool,
                mean_volume_db: float | None, silence_share: float | None) -> list[dict]:
    """Atom dicts in the creative_dna format (type/value/confidence/extracted_by)."""
    out: list[dict] = []
    pace = edit_pace_label(cuts, duration_sec)
    if pace:
        out.append({"type": "EDIT_PACE", "value": pace, "confidence": 0.90, "extracted_by": "ffmpeg",
                    "meta": {"cuts": cuts, "cuts_per_minute": cuts_per_minute(cuts, duration_sec)}})
    label = music_label(has_audio, mean_volume_db, silence_share)
    out.append({"type": "MUSIC", "value": label,
                "confidence": 0.85 if has_audio else 1.0, "extracted_by": "ffmpeg",
                "meta": {"mean_volume_db": mean_volume_db, "silence_share": silence_share,
                         "has_music": has_music(label)}})
    return out


# ─── ffmpeg runners (I/O) ───────────────────────────────────────────────────

def ffmpeg_available() -> bool:
    return shutil.which("ffmpeg") is not None and shutil.which("ffprobe") is not None


def probe_duration_and_audio(path: str, timeout: int = 60) -> tuple[float | None, bool]:
    """(duration_sec, has_audio_stream) via ffprobe."""
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_type",
                        "-of", "default=noprint_wrappers=1", path], capture_output=True, text=True, timeout=timeout)
    out = r.stdout or ""
    dur = None
    m = re.search(r"duration=([0-9]+(?:\.[0-9]+)?)", out)
    if m:
        dur = float(m.group(1))
    return dur, "codec_type=audio" in out


def detect_scene_cuts(path: str, threshold: float = SCENE_THRESHOLD, timeout: int = 300) -> int:
    r = subprocess.run(["ffmpeg", "-hide_banner", "-i", path, "-vf", f"select='gt(scene,{threshold})',showinfo",
                        "-an", "-f", "null", "-"], capture_output=True, text=True, timeout=timeout)
    return len(parse_scene_timestamps(r.stderr or ""))


def audio_profile(path: str, duration_sec: float | None, timeout: int = 300) -> tuple[float | None, float | None]:
    """(mean_volume_db, silence_share)."""
    r = subprocess.run(["ffmpeg", "-hide_banner", "-i", path, "-vn",
                        "-af", f"volumedetect,silencedetect=noise={SILENCE_DB}dB:d={SILENCE_MIN_SEC}",
                        "-f", "null", "-"], capture_output=True, text=True, timeout=timeout)
    mean_db, _ = parse_volume(r.stderr or "")
    return mean_db, parse_silence_share(r.stderr or "", duration_sec)


def extract_media_atoms_from_file(path: str, duration_hint: float | None = None) -> list[dict]:
    """Full pipeline on a local file. Returns [] when ffmpeg is not installed."""
    if not ffmpeg_available():
        return []
    duration, has_audio = probe_duration_and_audio(path)
    duration = duration or duration_hint
    cuts = detect_scene_cuts(path)
    mean_db, silence = audio_profile(path, duration) if has_audio else (None, None)
    return media_atoms(cuts, duration, has_audio, mean_db, silence)
