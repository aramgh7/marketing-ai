"""Roadmap 9-14: TTS — provider payloads, provider choice, stub output, voice atoms."""
import os
import wave

import pytest

from app.services.tts_service import (
    ELEVENLABS_MODEL,
    OPENAI_MODEL,
    build_elevenlabs_request,
    build_openai_request,
    choose_provider,
    estimate_duration_sec,
    silent_wav_bytes,
    synthesize,
    voice_atoms_from_settings,
)


def test_openai_request_shape_and_clamps():
    req = build_openai_request("  Hello   world ", voice="Nova", speed=9.0)
    assert req["model"] == OPENAI_MODEL and req["voice"] == "nova"
    assert req["input"] == "Hello world" and req["speed"] == 4.0 and req["response_format"] == "mp3"
    with pytest.raises(ValueError):
        build_openai_request("hi", voice="robot")
    with pytest.raises(ValueError):
        build_openai_request("   ")
    with pytest.raises(ValueError):
        build_openai_request("x" * 5000)


def test_elevenlabs_request_shape():
    req = build_elevenlabs_request("Hello", voice_id="abc123", speed=2.0)
    assert req["url"].endswith("/v1/text-to-speech/abc123")
    assert "xi-api-key" in req["headers"]
    assert req["json"]["model_id"] == ELEVENLABS_MODEL and req["json"]["text"] == "Hello"
    assert req["json"]["voice_settings"]["speed"] == 1.2           # clamped to the provider range
    with pytest.raises(ValueError):
        build_elevenlabs_request("Hello", voice_id="")


def test_duration_estimate():
    assert estimate_duration_sec("one two three four five six seven eight nine ten") == 4.0   # 10 words @150 wpm
    assert estimate_duration_sec("one two three four five six seven eight nine ten", speed=2.0) == 2.0
    assert estimate_duration_sec("") == 0.0


def test_choose_provider_rules():
    assert choose_provider("openai", "k", None) == "openai"
    assert choose_provider("elevenlabs", "k", None) == "openai"          # requested key missing → configured one
    assert choose_provider("elevenlabs", None, "e") == "elevenlabs"
    assert choose_provider(None, None, "e") == "elevenlabs"
    assert choose_provider(None, None, None) == "stub"


def test_silent_wav_is_valid_and_sized():
    data = silent_wav_bytes(1.5, sample_rate=8000)
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE"
    import io
    with wave.open(io.BytesIO(data)) as w:
        assert w.getnchannels() == 1 and w.getframerate() == 8000 and w.getnframes() == 12000


def test_synthesize_stub_without_keys(tmp_path):
    res = synthesize("Book a call today with our founder.", voice="alloy", speed=1.0,
                     out_dir=str(tmp_path), name="voice")
    assert res["provider"] == "stub" and res["stub"] is True and res["format"] == "wav"
    assert os.path.exists(res["path"]) and res["path"].endswith("voice.wav")
    assert res["duration_sec_estimate"] == pytest.approx(2.8, abs=0.01)


def test_voice_atoms_mapping():
    atoms = {a["atom_type"]: a["atom_value"] for a in voice_atoms_from_settings("onyx", 0.8)}
    assert atoms == {"VOICE_TONE": "AUTHORITATIVE", "VOICE_PACE": "SLOW", "VOICE_TIMBRE": "LOW"}
    atoms = {a["atom_type"]: a["atom_value"] for a in voice_atoms_from_settings("nova", 1.3)}
    assert atoms["VOICE_TONE"] == "WARM" and atoms["VOICE_PACE"] == "FAST" and atoms["VOICE_TIMBRE"] == "HIGH"
    atoms = {a["atom_type"]: a["atom_value"] for a in voice_atoms_from_settings("xyz", 1.0, provider="elevenlabs")}
    assert atoms == {"VOICE_TONE": "NEUTRAL", "VOICE_PACE": "NORMAL", "VOICE_TIMBRE": "MID"}
    assert all(a["extracted_by"] == "tts-settings" for a in voice_atoms_from_settings("alloy"))
