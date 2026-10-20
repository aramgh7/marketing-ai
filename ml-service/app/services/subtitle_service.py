"""Subtitles from Whisper timestamps (roadmap 9-13; vision §5.2) — pure logic, no FastAPI.

  segments_to_srt(segments)                 → SRT text  ("HH:MM:SS,mmm --> HH:MM:SS,mmm")
  segments_to_ass(segments, style=...)      → ASS text  ([Script Info] / [V4+ Styles] / [Events])
  wrap_text(text, max_chars, max_lines)     → list of lines (word wrap, ≤ max_lines)
  segments_from_transcript(text, duration)  → naive even timing when Whisper segments are absent
  write_subtitle_file(text, suffix, out_dir)→ path of the written file (UTF-8)

Styles (ASS [V4+ Styles], PlayRes 1080x1920 by default):
  white_bottom  white text, black outline, bottom centre     (Alignment 2)
  yellow_bold   bold yellow, black outline, bottom centre    (Alignment 2)
  white_top     white text, black outline, top centre        (Alignment 8)
  black_box     white text on an opaque black box, bottom    (BorderStyle 3, Alignment 2)
  none          → no subtitles (callers should skip the filter)

Segment shape (as produced by transcription_service): {"start": float, "end": float, "text": str}.
"""
from __future__ import annotations

import os
import re
import uuid

SUBTITLE_STYLES = ("white_bottom", "yellow_bold", "white_top", "black_box", "none")
DEFAULT_MAX_CHARS = 32
DEFAULT_MAX_LINES = 2
MIN_SEGMENT_SEC = 0.3       # a segment shorter than this is extended so it is readable
PLAY_RES = (1080, 1920)     # 9:16 default; callers pass the render resolution

# ASS colours are &HAABBGGRR (alpha, blue, green, red)
_ASS_STYLES: dict[str, dict[str, object]] = {
    "white_bottom": {"primary": "&H00FFFFFF", "outline": "&H00000000", "back": "&H80000000",
                     "bold": 0, "border_style": 1, "outline_w": 3, "shadow": 1, "alignment": 2},
    "yellow_bold":  {"primary": "&H0000FFFF", "outline": "&H00000000", "back": "&H80000000",
                     "bold": -1, "border_style": 1, "outline_w": 3, "shadow": 1, "alignment": 2},
    "white_top":    {"primary": "&H00FFFFFF", "outline": "&H00000000", "back": "&H80000000",
                     "bold": 0, "border_style": 1, "outline_w": 3, "shadow": 1, "alignment": 8},
    "black_box":    {"primary": "&H00FFFFFF", "outline": "&H00000000", "back": "&H00000000",
                     "bold": 0, "border_style": 3, "outline_w": 6, "shadow": 0, "alignment": 2},
}


# ─────────────────────────────────────────────────────────────────────────────
# Time formatting
# ─────────────────────────────────────────────────────────────────────────────

def format_srt_time(seconds: float) -> str:
    """SRT: HH:MM:SS,mmm."""
    ms_total = int(round(max(0.0, float(seconds)) * 1000))
    h, rem = divmod(ms_total, 3_600_000)
    m, rem = divmod(rem, 60_000)
    s, ms = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def format_ass_time(seconds: float) -> str:
    """ASS: H:MM:SS.cc (centiseconds)."""
    cs_total = int(round(max(0.0, float(seconds)) * 100))
    h, rem = divmod(cs_total, 360_000)
    m, rem = divmod(rem, 6_000)
    s, cs = divmod(rem, 100)
    return f"{h:d}:{m:02d}:{s:02d}.{cs:02d}"


# ─────────────────────────────────────────────────────────────────────────────
# Text helpers
# ─────────────────────────────────────────────────────────────────────────────

def wrap_text(text: str, max_chars: int = DEFAULT_MAX_CHARS, max_lines: int = DEFAULT_MAX_LINES) -> list[str]:
    """Greedy word wrap into at most `max_lines` lines of ~`max_chars`.

    If the text does not fit, the last line absorbs the remainder (never dropped — a subtitle
    that silently loses words is worse than one that is a little long).
    """
    words = text.split()
    if not words:
        return []
    lines: list[str] = []
    current = ""
    for w in words:
        candidate = f"{current} {w}".strip()
        if current and len(candidate) > max_chars and len(lines) < max_lines - 1:
            lines.append(current)
            current = w
        else:
            current = candidate
    if current:
        lines.append(current)
    if len(lines) > max_lines:
        lines = lines[:max_lines - 1] + [" ".join(lines[max_lines - 1:])]
    return lines


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", str(text or "")).strip()


def escape_ass_text(text: str) -> str:
    """ASS override blocks live in braces; a literal brace would be parsed as a tag."""
    return (text.replace("\\", "\\\\")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("\r", "")
                .replace("\n", "\\N"))


def _normalise_segments(segments: list[dict]) -> list[dict]:
    """Drop empty segments, clamp negative times, enforce start < end and chronological order."""
    out: list[dict] = []
    for seg in segments or []:
        text = _clean(seg.get("text", ""))
        if not text:
            continue
        start = max(0.0, float(seg.get("start", 0.0) or 0.0))
        end = float(seg.get("end", start) or start)
        if end - start < MIN_SEGMENT_SEC:
            end = start + MIN_SEGMENT_SEC
        out.append({"start": start, "end": end, "text": text})
    out.sort(key=lambda s: s["start"])
    # No overlaps: a later segment starts no earlier than the previous one ends
    for i in range(1, len(out)):
        if out[i]["start"] < out[i - 1]["end"]:
            out[i - 1]["end"] = max(out[i - 1]["start"] + MIN_SEGMENT_SEC, out[i]["start"])
    return out


def segments_from_transcript(text: str, duration_sec: float, max_chars: int = DEFAULT_MAX_CHARS * 2) -> list[dict]:
    """Naive timing when only a transcript is available: split into sentence-ish chunks and spread
    them proportionally to their character length over `duration_sec`."""
    text = _clean(text)
    if not text or duration_sec <= 0:
        return []
    raw_chunks = re.split(r"(?<=[.!?])\s+", text)
    chunks: list[str] = []
    for c in raw_chunks:
        c = c.strip()
        if not c:
            continue
        if len(c) <= max_chars:
            chunks.append(c)
        else:
            words = c.split()
            cur = ""
            for w in words:
                cand = f"{cur} {w}".strip()
                if cur and len(cand) > max_chars:
                    chunks.append(cur)
                    cur = w
                else:
                    cur = cand
            if cur:
                chunks.append(cur)
    total_chars = sum(len(c) for c in chunks) or 1
    segments: list[dict] = []
    t = 0.0
    for c in chunks:
        dur = duration_sec * len(c) / total_chars
        segments.append({"start": round(t, 3), "end": round(t + dur, 3), "text": c})
        t += dur
    if segments:
        segments[-1]["end"] = round(float(duration_sec), 3)
    return segments


# ─────────────────────────────────────────────────────────────────────────────
# Writers
# ─────────────────────────────────────────────────────────────────────────────

def segments_to_srt(segments: list[dict], max_chars: int = DEFAULT_MAX_CHARS,
                    max_lines: int = DEFAULT_MAX_LINES) -> str:
    blocks: list[str] = []
    for i, seg in enumerate(_normalise_segments(segments), start=1):
        lines = wrap_text(seg["text"], max_chars, max_lines)
        blocks.append(f"{i}\n{format_srt_time(seg['start'])} --> {format_srt_time(seg['end'])}\n" + "\n".join(lines))
    return "\n\n".join(blocks) + ("\n" if blocks else "")


def segments_to_ass(segments: list[dict], style: str = "white_bottom",
                    play_res: tuple[int, int] = PLAY_RES, font: str = "Arial", font_size: int | None = None,
                    max_chars: int = DEFAULT_MAX_CHARS, max_lines: int = DEFAULT_MAX_LINES) -> str:
    """Build an ASS file. `style` must be one of the burn-in styles (not 'none')."""
    if style not in _ASS_STYLES:
        raise ValueError(f"unknown subtitle style '{style}'; expected one of {sorted(_ASS_STYLES)}")
    st = _ASS_STYLES[style]
    w, h = play_res
    size = font_size or max(36, int(h * 0.034))      # ≈ 65 px on 1920
    margin_v = int(h * 0.08)
    header = (
        "[Script Info]\n"
        "ScriptType: v4.00+\n"
        f"PlayResX: {w}\n"
        f"PlayResY: {h}\n"
        "WrapStyle: 2\n"
        "ScaledBorderAndShadow: yes\n\n"
        "[V4+ Styles]\n"
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, "
        "Alignment, MarginL, MarginR, MarginV, Encoding\n"
        f"Style: Default,{font},{size},{st['primary']},&H000000FF,{st['outline']},{st['back']},"
        f"{st['bold']},0,0,0,100,100,0,0,{st['border_style']},{st['outline_w']},{st['shadow']},"
        f"{st['alignment']},60,60,{margin_v},1\n\n"
        "[Events]\n"
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
    )
    events: list[str] = []
    for seg in _normalise_segments(segments):
        lines = wrap_text(seg["text"], max_chars, max_lines)
        text = "\\N".join(escape_ass_text(line) for line in lines)
        events.append(f"Dialogue: 0,{format_ass_time(seg['start'])},{format_ass_time(seg['end'])},Default,,0,0,0,,{text}")
    return header + "\n".join(events) + ("\n" if events else "")


def write_subtitle_file(text: str, suffix: str, out_dir: str, name: str | None = None) -> str:
    """Write subtitle text to `out_dir/<name or uuid><suffix>` (UTF-8, LF). Returns the path."""
    if not suffix.startswith("."):
        suffix = "." + suffix
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, f"{name or uuid.uuid4()}{suffix}")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return path
