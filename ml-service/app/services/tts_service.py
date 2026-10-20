"""Text-to-speech (roadmap 9-14; vision §5.2 "AI voice") — pure logic, no FastAPI.

Providers
  openai       `tts-1` via the official SDK (lazy import); voices alloy / echo / fable / onyx / nova /
               shimmer; `speed` 0.25–4.0; output mp3
  elevenlabs   POST https://api.elevenlabs.io/v1/text-to-speech/{voice_id} (header `xi-api-key`);
               `voice` is the ElevenLabs voice id; output mp3
  stub         no key configured → a silent 16 kHz mono WAV of the estimated duration, so the
               render pipeline and tests work end-to-end without external calls

Entry points
  synthesize(text, voice, speed, provider, out_dir, api_keys) → {"path", "provider", "voice",
      "speed", "duration_sec_estimate", "format", "stub": bool}
  build_openai_request(...) / build_elevenlabs_request(...)  → payload dicts (unit-testable)
  estimate_duration_sec(text, speed)                        → words / (150 wpm × speed)
  voice_atoms_from_settings(voice, speed, provider)         → VOICE_TONE / VOICE_PACE / VOICE_TIMBRE

Mapping used for the VOICE_* atoms (documented so the brief can explain them):
  VOICE_PACE   speed < 0.9 → SLOW, 0.9–1.1 → NORMAL, > 1.1 → FAST
  VOICE_TONE   openai: alloy/nova/shimmer → WARM, echo/fable → NEUTRAL, onyx → AUTHORITATIVE;
               elevenlabs / unknown voice → NEUTRAL
  VOICE_TIMBRE openai: onyx/echo → LOW, alloy/fable → MID, nova/shimmer → HIGH; else MID
"""
from __future__ import annotations

import io
import os
import struct
import uuid
import wave

OPENAI_VOICES = ("alloy", "echo", "fable", "onyx", "nova", "shimmer")
OPENAI_MODEL = "tts-1"
ELEVENLABS_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice_id}"
ELEVENLABS_MODEL = "eleven_multilingual_v2"
WORDS_PER_MINUTE = 150.0
MAX_TEXT_CHARS = 4096          # OpenAI hard limit per request
STUB_SAMPLE_RATE = 16_000

_TONE = {"alloy": "WARM", "nova": "WARM", "shimmer": "WARM", "echo": "NEUTRAL", "fable": "NEUTRAL",
         "onyx": "AUTHORITATIVE"}
_TIMBRE = {"onyx": "LOW", "echo": "LOW", "alloy": "MID", "fable": "MID", "nova": "HIGH", "shimmer": "HIGH"}


def estimate_duration_sec(text: str, speed: float = 1.0) -> float:
    words = len(str(text or "").split())
    speed = max(0.25, min(4.0, float(speed or 1.0)))
    return round(words / (WORDS_PER_MINUTE * speed) * 60.0, 2) if words else 0.0


def validate_text(text: str) -> str:
    t = " ".join(str(text or "").split())
    if not t:
        raise ValueError("text is empty")
    if len(t) > MAX_TEXT_CHARS:
        raise ValueError(f"text longer than {MAX_TEXT_CHARS} characters; split it into segments")
    return t


def build_openai_request(text: str, voice: str = "alloy", speed: float = 1.0, fmt: str = "mp3") -> dict:
    voice = (voice or "alloy").lower()
    if voice not in OPENAI_VOICES:
        raise ValueError(f"unknown OpenAI voice '{voice}'; expected one of {OPENAI_VOICES}")
    return {"model": OPENAI_MODEL, "voice": voice, "input": validate_text(text),
            "speed": max(0.25, min(4.0, float(speed or 1.0))), "response_format": fmt}


def build_elevenlabs_request(text: str, voice_id: str, speed: float = 1.0, stability: float = 0.5,
                             similarity_boost: float = 0.75) -> dict:
    if not voice_id:
        raise ValueError("ElevenLabs voice_id is required")
    return {
        "url": ELEVENLABS_URL.format(voice_id=voice_id),
        "headers": {"xi-api-key": "<set at call time>", "accept": "audio/mpeg", "content-type": "application/json"},
        "json": {
            "text": validate_text(text),
            "model_id": ELEVENLABS_MODEL,
            "voice_settings": {"stability": stability, "similarity_boost": similarity_boost,
                               "speed": max(0.7, min(1.2, float(speed or 1.0)))},
        },
    }


def voice_atoms_from_settings(voice: str, speed: float = 1.0, provider: str = "openai") -> list[dict]:
    """VOICE_* atoms derived from the real synthesis settings (not from a free-text style)."""
    speed = float(speed or 1.0)
    pace = "SLOW" if speed < 0.9 else ("FAST" if speed > 1.1 else "NORMAL")
    v = (voice or "").lower()
    if (provider or "openai").lower() == "openai":
        tone, timbre = _TONE.get(v, "NEUTRAL"), _TIMBRE.get(v, "MID")
    else:
        tone, timbre = "NEUTRAL", "MID"
    return [
        {"atom_type": "VOICE_TONE", "atom_value": tone, "confidence": 0.9, "extracted_by": "tts-settings"},
        {"atom_type": "VOICE_PACE", "atom_value": pace, "confidence": 0.95, "extracted_by": "tts-settings"},
        {"atom_type": "VOICE_TIMBRE", "atom_value": timbre, "confidence": 0.8, "extracted_by": "tts-settings"},
    ]


def silent_wav_bytes(duration_sec: float, sample_rate: int = STUB_SAMPLE_RATE) -> bytes:
    """A valid silent 16-bit mono WAV — the stub output when no provider key is configured."""
    n = max(1, int(round(max(0.1, duration_sec) * sample_rate)))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(struct.pack("<h", 0) * n)
    return buf.getvalue()


def choose_provider(provider: str | None, openai_key: str | None, elevenlabs_key: str | None,
                    default: str = "openai") -> str:
    """Explicit provider wins when its key exists; otherwise the first configured one; else stub."""
    p = (provider or default or "openai").lower()
    if p == "elevenlabs" and elevenlabs_key:
        return "elevenlabs"
    if p == "openai" and openai_key:
        return "openai"
    if openai_key:
        return "openai"
    if elevenlabs_key:
        return "elevenlabs"
    return "stub"


def synthesize(
    text: str,
    voice: str = "alloy",
    speed: float = 1.0,
    provider: str | None = None,
    out_dir: str = ".",
    openai_key: str | None = None,
    elevenlabs_key: str | None = None,
    name: str | None = None,
) -> dict:
    """Synthesize `text` to an audio file under `out_dir`. Never raises for a missing key — falls
    back to the stub so renders keep working in dev."""
    text = validate_text(text)
    chosen = choose_provider(provider, openai_key, elevenlabs_key)
    os.makedirs(out_dir, exist_ok=True)
    base = name or str(uuid.uuid4())
    duration = estimate_duration_sec(text, speed)

    if chosen == "openai":
        import openai  # lazy
        req = build_openai_request(text, voice, speed)
        client = openai.OpenAI(api_key=openai_key)
        resp = client.audio.speech.create(**req)
        path = os.path.join(out_dir, f"{base}.mp3")
        data = resp.content if hasattr(resp, "content") else resp.read()
        with open(path, "wb") as f:
            f.write(data)
        return {"path": path, "provider": "openai", "voice": req["voice"], "speed": req["speed"],
                "duration_sec_estimate": duration, "format": "mp3", "stub": False}

    if chosen == "elevenlabs":
        import httpx  # lazy
        req = build_elevenlabs_request(text, voice, speed)
        headers = dict(req["headers"], **{"xi-api-key": elevenlabs_key})
        r = httpx.post(req["url"], headers=headers, json=req["json"], timeout=120)
        r.raise_for_status()
        path = os.path.join(out_dir, f"{base}.mp3")
        with open(path, "wb") as f:
            f.write(r.content)
        return {"path": path, "provider": "elevenlabs", "voice": voice, "speed": float(speed or 1.0),
                "duration_sec_estimate": duration, "format": "mp3", "stub": False}

    path = os.path.join(out_dir, f"{base}.wav")
    with open(path, "wb") as f:
        f.write(silent_wav_bytes(duration))
    return {"path": path, "provider": "stub", "voice": voice, "speed": float(speed or 1.0),
            "duration_sec_estimate": duration, "format": "wav", "stub": True}
