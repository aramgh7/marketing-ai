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

def build_ffmpeg_cmd(src: str, dst: str, plan: list[dict], scale: str) -> list[str]:
    """
    Build an FFmpeg command list for rendering a montage plan.

    Uses list-style args (no shell=True) to prevent shell injection.
    Escapes text overlay content for the drawtext filter.
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
    vf = ",".join(vf_parts)

    return [
        "ffmpeg", "-y",
        "-i", src,
        "-t", str(total_sec),
        "-vf", vf,
        "-c:v", "libx264", "-preset", "fast", "-crf", "23",
        "-c:a", "aac", "-b:a", "128k",
        "-movflags", "+faststart",
        dst,
    ]


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
