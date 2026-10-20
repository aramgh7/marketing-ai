"""Music bed selection (roadmap 9-15; vision §5.2) — pure logic, no FastAPI.

The platform does not generate music: no generative-music provider offers an official API with a
commercial licence we can verify, so tenants upload tracks they are licensed to use and the render
mixes one of them under the speech (see video_service.build_audio_filter_graph).

Track row (table `music_tracks`, V131): {id, tenant_id, url, title, licence, bpm, mood, duration_sec}
  licence ∈ LICENCES = {royalty_free, licensed, owned}; anything else is rejected before use
  mood    free label normalised to lower case (calm, upbeat, dramatic, corporate, …)

  validate_track(track)                     → list of error strings (empty = ok)
  choose_track(tracks, mood, bpm_range)     → best track or None (mood match, then bpm, then shortest-enough)
  music_atom_for_track(track)               → {"atom_type": "MUSIC", "atom_value": "<mood>_<bpm bucket>"}
  bpm_bucket(bpm)                           → SLOW (<90) / MID (90–120) / FAST (>120) / UNKNOWN
"""
from __future__ import annotations

LICENCES = ("royalty_free", "licensed", "owned")
DEFAULT_GAIN_DB = -12.0


def bpm_bucket(bpm: float | int | None) -> str:
    if bpm is None:
        return "UNKNOWN"
    try:
        b = float(bpm)
    except (TypeError, ValueError):
        return "UNKNOWN"
    if b <= 0:
        return "UNKNOWN"
    if b < 90:
        return "SLOW"
    if b <= 120:
        return "MID"
    return "FAST"


def validate_track(track: dict) -> list[str]:
    errors: list[str] = []
    if not track or not str(track.get("url", "")).strip():
        errors.append("url is required")
    licence = str(track.get("licence", "") or "").lower()
    if licence not in LICENCES:
        errors.append(f"licence must be one of {LICENCES}")
    bpm = track.get("bpm")
    if bpm is not None:
        try:
            if float(bpm) <= 0:
                errors.append("bpm must be positive")
        except (TypeError, ValueError):
            errors.append("bpm must be numeric")
    dur = track.get("duration_sec")
    if dur is not None:
        try:
            if float(dur) <= 0:
                errors.append("duration_sec must be positive")
        except (TypeError, ValueError):
            errors.append("duration_sec must be numeric")
    return errors


def _mood(track: dict) -> str:
    return str(track.get("mood", "") or "").strip().lower()


def choose_track(
    tracks: list[dict],
    mood: str | None = None,
    bpm_range: tuple[float, float] | None = None,
    min_duration_sec: float | None = None,
) -> dict | None:
    """Pick one licensed track.

    Ranking: exact mood match > bpm inside the range > duration ≥ min_duration (no looping needed)
    > lower id for determinism. Tracks failing validate_track are skipped.
    """
    candidates = [t for t in tracks or [] if not validate_track(t)]
    if not candidates:
        return None
    want_mood = (mood or "").strip().lower()

    def score(t: dict) -> tuple:
        s_mood = 1 if want_mood and _mood(t) == want_mood else 0
        s_bpm = 0
        if bpm_range and t.get("bpm") is not None:
            lo, hi = bpm_range
            try:
                s_bpm = 1 if lo <= float(t["bpm"]) <= hi else 0
            except (TypeError, ValueError):
                s_bpm = 0
        s_dur = 0
        if min_duration_sec and t.get("duration_sec") is not None:
            try:
                s_dur = 1 if float(t["duration_sec"]) >= float(min_duration_sec) else 0
            except (TypeError, ValueError):
                s_dur = 0
        return (s_mood, s_bpm, s_dur, -_id_key(t))

    # highest score first; ties broken by the lower id for determinism
    return max(candidates, key=score)


def _id_key(t: dict) -> float:
    """Lower ids rank first on ties; non-numeric ids fall back to a stable hash."""
    raw = str(t.get("id", ""))
    try:
        return float(raw)
    except ValueError:
        return float(abs(hash(raw)) % 10_000_000)


def music_atom_for_track(track: dict) -> dict:
    mood = _mood(track) or "unspecified"
    return {
        "atom_type": "MUSIC",
        "atom_value": f"{mood}_{bpm_bucket(track.get('bpm'))}",
        "confidence": 1.0,
        "extracted_by": "music-library",
        "track_id": str(track.get("id", "")) or None,
    }


def render_music_option(track: dict, gain_db: float | None = None, duck: bool = True) -> dict:
    """The `music` dict consumed by video_service.build_ffmpeg_cmd (path filled in by the router
    after download)."""
    errs = validate_track(track)
    if errs:
        raise ValueError("; ".join(errs))
    return {"url": track["url"], "gain_db": DEFAULT_GAIN_DB if gain_db is None else float(gain_db),
            "duck": bool(duck), "track_id": str(track.get("id", "")) or None}
