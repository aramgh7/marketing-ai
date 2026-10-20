"""
Pure-logic functions for the Video Template Engine (Phase 4-1, 4-2).

Extracted from the FastAPI router so they can be unit-tested without a FastAPI context.
No external HTTP calls, no DB, no FFmpeg subprocess here — only data transformations.
"""
import json
import uuid
from typing import Any


# ──────────────────────────────────────────────────────────────────────────────
# Format → FFmpeg scale string
# ──────────────────────────────────────────────────────────────────────────────

FORMAT_SCALE: dict[str, str] = {
    "9:16": "1080:1920",
    "4:5":  "1080:1350",
    "1:1":  "1080:1080",
}

SUBTITLE_STYLES = ["white_bottom", "yellow_bold", "white_top", "black_box", "none"]

# Stubs used when API keys are missing (and in unit tests)
STUB_TRANSCRIPT = "This is a stub transcript. Product demo: apply the gel to the affected area."
STUB_SCENES: list[dict] = [
    {"start_sec": 0.0,  "end_sec": 3.0,  "description": "Hook — question to viewer",
     "tags": ["hook", "face", "direct_address"], "recommended_for": "hook"},
    {"start_sec": 3.0,  "end_sec": 12.0, "description": "Problem statement",
     "tags": ["problem", "pain_point"], "recommended_for": "problem"},
    {"start_sec": 12.0, "end_sec": 22.0, "description": "Product demo",
     "tags": ["solution", "demo", "product"], "recommended_for": "solution"},
    {"start_sec": 22.0, "end_sec": 27.0, "description": "Social proof",
     "tags": ["proof", "testimonial"], "recommended_for": "proof"},
    {"start_sec": 27.0, "end_sec": 30.0, "description": "CTA",
     "tags": ["cta", "offer"], "recommended_for": "cta"},
]
STUB_PLAN: list[dict] = [
    {"segment": "hook",     "scenes": [0], "duration_sec": 3,  "text_overlay": ""},
    {"segment": "problem",  "scenes": [1], "duration_sec": 9,  "text_overlay": ""},
    {"segment": "solution", "scenes": [2], "duration_sec": 10, "text_overlay": ""},
    {"segment": "proof",    "scenes": [3], "duration_sec": 5,  "text_overlay": "Social proof"},
    {"segment": "cta",      "scenes": [4], "duration_sec": 3,  "text_overlay": "Try it now →"},
]


# ──────────────────────────────────────────────────────────────────────────────
# Variation generation (§5.2, Phase 4-2)
# ──────────────────────────────────────────────────────────────────────────────

def build_variations(
    tenant_id: str,
    footage_id: str,
    footage_url: str,
    hooks: list[str],
    bodies: list[str],
    ctas: list[str],
    formats: list[str],
    add_subtitle: bool,
) -> list[dict[str, Any]]:
    """
    Build Hook × Body × CTA variation matrix plus optional subtitle style variations.

    3 hooks × 1 body × 3 CTAs × 1 format = 9 HOOK_VARIATION specs.
    add_subtitle=True appends 5 OVERLAY_VARIATION specs (same content, different overlay style).

    Returns plain dicts (no Pydantic) for testability.
    """
    variations: list[dict[str, Any]] = []

    for fmt in formats:
        for hook in hooks:
            for body in bodies:
                for cta in ctas:
                    variations.append({
                        "variation_id": str(uuid.uuid4()),
                        "variation_type": "HOOK_VARIATION",
                        "hook_text": hook,
                        "body_text": body,
                        "cta_text": cta,
                        "format": fmt,
                        "render_spec": {
                            "footage_url": footage_url,
                            "format": fmt,
                            "hook_overlay": hook,
                            "body_overlay": body,
                            "cta_overlay": cta,
                            "text_overlays": {"hook": hook, "cta": cta},
                        },
                    })

        # Low-effort subtitle variations: same content, different text style
        if add_subtitle and hooks and bodies and ctas:
            base_hook = hooks[0]
            base_cta  = ctas[0]
            for style in SUBTITLE_STYLES:
                variations.append({
                    "variation_id": str(uuid.uuid4()),
                    "variation_type": "OVERLAY_VARIATION",
                    "hook_text": base_hook,
                    "body_text": bodies[0],
                    "cta_text": base_cta,
                    "format": fmt,
                    "render_spec": {
                        "footage_url": footage_url,
                        "format": fmt,
                        "hook_overlay": base_hook,
                        "cta_overlay": base_cta,
                        "subtitle_style": style,
                        "text_overlays": {"hook": base_hook, "cta": base_cta},
                    },
                })

    return variations


# ──────────────────────────────────────────────────────────────────────────────
# FFmpeg command building (Phase 4-1)
# ──────────────────────────────────────────────────────────────────────────────

def escape_filter_path(path: str) -> str:
    """Escape a file path for use inside an ffmpeg filter option (e.g. `subtitles=<path>`).

    Backslashes become forward slashes (ffmpeg accepts them on Windows), the drive colon and any
    other colon are escaped as `\\:` so the filter parser does not read them as option separators,
    and single quotes are escaped.
    """
    p = str(path).replace("\\", "/")
    return p.replace("'", "\\'").replace(":", "\\:")


def _volume_filter(gain_db: float | None) -> str:
    g = float(gain_db or 0.0)
    return f"volume={g:+.1f}dB" if abs(g) > 1e-9 else "anull"


def build_audio_filter_graph(
    voiceover: dict | None = None,
    music: dict | None = None,
    voice_input_index: int = 1,
    music_input_index: int | None = None,
) -> dict:
    """Build the audio part of the render (roadmap 9-14 / 9-15) — pure, no I/O.

    Inputs are ffmpeg input indices: 0 is always the footage; the voice-over (if any) is input
    `voice_input_index`; the music (if any) is `music_input_index` (defaults to the next index).

    voiceover = {"path": ..., "mode": "replace" | "mix", "gain_db": 0.0}
        replace → the voice is the only audio track (original audio dropped)
        mix     → `amix` of the original track and the voice (duration=first = footage length)
    music     = {"path": ..., "gain_db": -12.0, "duck": True}
        mixed under whatever audio exists; with duck=True a `sidechaincompress` keyed by the
        voice (or the original track when there is no voice) lowers the music while speech plays.

    Returns {"filter_complex": str | None, "map_args": [...], "has_audio_filter": bool}.
    With neither voiceover nor music the result is empty and callers keep `-vf` only.
    """
    if not voiceover and not music:
        return {"filter_complex": None, "map_args": [], "has_audio_filter": False}

    chains: list[str] = []
    labels: list[str] = []        # audio streams to mix at the end
    speech_label: str | None = None   # key for ducking

    if voiceover:
        mode = str(voiceover.get("mode", "mix")).lower()
        if mode not in ("replace", "mix"):
            raise ValueError("voiceover.mode must be 'replace' or 'mix'")
        chains.append(f"[{voice_input_index}:a]{_volume_filter(voiceover.get('gain_db'))}[voice]")
        speech_label = "voice"
        if mode == "mix":
            chains.append("[0:a]anull[orig]")
            chains.append("[orig][voice]amix=inputs=2:duration=first:dropout_transition=2[speech]")
            speech_label = "speech"
        labels.append(speech_label)
    else:
        chains.append("[0:a]anull[orig]")
        speech_label = "orig"
        labels.append("orig")

    if music:
        m_idx = music_input_index if music_input_index is not None else (voice_input_index + 1 if voiceover else voice_input_index)
        chains.append(f"[{m_idx}:a]{_volume_filter(music.get('gain_db', -12.0))}[music_raw]")
        if music.get("duck", True):
            # asplit so the speech stream can both key the compressor and be mixed afterwards
            chains.append(f"[{speech_label}]asplit=2[{speech_label}_key][{speech_label}_mix]")
            chains.append(
                f"[music_raw][{speech_label}_key]sidechaincompress=threshold=0.05:ratio=8:attack=20:release=400[music]"
            )
            labels = [f"{speech_label}_mix"]
        else:
            chains.append("[music_raw]anull[music]")
        labels.append("music")

    if len(labels) == 1:
        chains.append(f"[{labels[0]}]anull[aout]")
    else:
        chains.append("[" + "][".join(labels) + f"]amix=inputs={len(labels)}:duration=first:dropout_transition=2[aout]")

    return {
        "filter_complex": ";".join(chains),
        "map_args": ["-map", "0:v", "-map", "[aout]"],
        "has_audio_filter": True,
    }


def build_ffmpeg_cmd(
    src: str,
    dst: str,
    plan: list[dict],
    scale: str,
    subtitles_path: str | None = None,
    voiceover: dict | None = None,
    music: dict | None = None,
) -> list[str]:
    """
    Build an FFmpeg command list for rendering a montage plan.

    Uses list-style args (no shell=True) to prevent shell injection.
    Escapes text overlay content for the drawtext filter.

    Roadmap 9-13/9-14/9-15 extras (all optional, default behaviour unchanged):
      subtitles_path — ASS/SRT file burned in with the `subtitles=` filter (after scale/pad)
      voiceover      — {"path", "mode": "replace"|"mix", "gain_db"} extra audio input
      music          — {"path", "gain_db", "duck"} extra audio input mixed under the speech
    """
    w, h = scale.split(":")
    total_sec = sum(seg.get("duration_sec", 5) for seg in plan)

    drawtext_filters: list[str] = []
    elapsed = 0
    for seg in plan:
        dur     = seg.get("duration_sec", 5)
        overlay = seg.get("text_overlay", "").strip()
        if overlay:
            safe = _escape_ffmpeg_text(overlay)
            drawtext_filters.append(
                f"drawtext=text='{safe}':fontsize=48:fontcolor=white"
                f":x=(w-text_w)/2:y=h-100"
                f":enable='between(t,{elapsed},{elapsed + dur})'"
            )
        elapsed += dur

    vf_parts = [
        f"scale={w}:{h}:force_original_aspect_ratio=decrease",
        f"pad={w}:{h}:(ow-iw)/2:(oh-ih)/2:black",
    ]
    vf_parts.extend(drawtext_filters)
    if subtitles_path:
        vf_parts.append(f"subtitles='{escape_filter_path(subtitles_path)}'")
    vf = ",".join(vf_parts)

    inputs: list[str] = ["-i", src]
    next_idx = 1
    voice_idx = music_idx = None
    if voiceover and voiceover.get("path"):
        inputs += ["-i", str(voiceover["path"])]
        voice_idx = next_idx
        next_idx += 1
    if music and music.get("path"):
        # loop the bed so a short track covers the whole render; -t caps the output anyway
        inputs += ["-stream_loop", "-1", "-i", str(music["path"])]
        music_idx = next_idx
        next_idx += 1

    audio = build_audio_filter_graph(
        voiceover if voice_idx is not None else None,
        music if music_idx is not None else None,
        voice_input_index=voice_idx if voice_idx is not None else 1,
        music_input_index=music_idx,
    )

    cmd: list[str] = ["ffmpeg", "-y", *inputs, "-t", str(total_sec)]
    if audio["has_audio_filter"]:
        # -vf and -filter_complex cannot be combined; the video chain joins the complex graph
        cmd += ["-filter_complex", f"[0:v]{vf}[vout];{audio['filter_complex']}",
                "-map", "[vout]", "-map", "[aout]"]
    else:
        cmd += ["-vf", vf]
    cmd += [
        "-c:v", "libx264", "-preset", "fast", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
    ]
    if audio["has_audio_filter"]:
        cmd.append("-shortest")     # looped music bed must not extend the output
    cmd += ["-movflags", "+faststart", dst]
    return cmd


def _escape_ffmpeg_text(text: str) -> str:
    """Escape special characters for FFmpeg drawtext filter."""
    # Order matters: backslash first, then others
    return (text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")
            .replace(",", "\\,"))


# ──────────────────────────────────────────────────────────────────────────────
# JSON parsing for LLM responses
# ──────────────────────────────────────────────────────────────────────────────

def parse_json_list(raw: str) -> list[dict]:
    """Parse a JSON array from an LLM response that may contain markdown fences."""
    text = raw.strip()
    if text.startswith("```"):
        lines = text.split("\n")
        end_idx = next((i for i in range(len(lines) - 1, 0, -1) if lines[i].strip() == "```"), None)
        if end_idx:
            text = "\n".join(lines[1:end_idx])
        else:
            text = "\n".join(lines[1:])
    start = text.find("[")
    end   = text.rfind("]")
    if start == -1 or end == -1:
        raise ValueError(f"No JSON array found in LLM response: {raw[:200]}")
    return json.loads(text[start: end + 1])


# ──────────────────────────────────────────────────────────────────────────────
# Montage plan validation
# ──────────────────────────────────────────────────────────────────────────────

def validate_montage_plan(plan: list[dict]) -> list[str]:
    """
    Validate a montage plan. Returns list of error strings (empty = valid).

    Rules:
    - Must contain 'hook' and 'cta' segments (vision §5.2: mandatory structure)
    - Hook duration must be ≤3 seconds
    - Total duration must be positive
    """
    errors: list[str] = []
    segment_names = {s.get("segment") for s in plan}

    if "hook" not in segment_names:
        errors.append("Missing 'hook' segment")
    if "cta" not in segment_names:
        errors.append("Missing 'cta' segment")

    for seg in plan:
        if seg.get("segment") == "hook":
            if seg.get("duration_sec", 999) > 3:
                errors.append(f"Hook duration {seg['duration_sec']}s exceeds 3s limit (§5.2)")

    total = sum(s.get("duration_sec", 0) for s in plan)
    if total <= 0:
        errors.append("Plan has zero or negative total duration")

    return errors
