"""
AI Video / Voice-as-Atom service — no FastAPI/Pydantic imports.
Provider adapter: HeyGen / Synthesia / Runway / MOCK.
Mandatory A/B acceptance: P(AI >= live) > 0.9 before scale (vision §5.2).
Architecture §15.21, roadmap 5-6.
"""
from __future__ import annotations

import numpy as np

_RNG = np.random.default_rng(seed=42)

SUPPORTED_PROVIDERS = {"HEYGEN", "SYNTHESIA", "RUNWAY", "MOCK"}

# Minimum sessions per arm before we can compute acceptance
_MIN_SESSIONS_PER_ARM = 50
_ACCEPTANCE_THRESHOLD = 0.9   # P(AI >= live) must exceed this to PASS
_MC_SAMPLES = 10_000


def validate_provider(provider: str) -> dict:
    """Check provider is supported and feature-flag accepted."""
    p = provider.upper()
    if p not in SUPPORTED_PROVIDERS:
        return {"valid": False, "error": f"Unknown provider '{provider}'. Supported: {sorted(SUPPORTED_PROVIDERS)}"}
    return {"valid": True, "provider": p}


def build_provider_payload(
    provider: str,
    script_text: str,
    voice_settings: dict,
    format_settings: dict,
) -> dict:
    """
    Build the provider-specific API payload for video generation.
    Returns {provider, payload, requires_ai_label: True}.
    """
    p = provider.upper()

    base = {
        "script": script_text,
        "voice": voice_settings,
        "format": format_settings,
    }

    if p == "HEYGEN":
        payload = {
            "video_inputs": [{
                "character": {"type": "avatar", "avatar_id": voice_settings.get("avatar_id", "default")},
                "voice": {
                    "type": "text",
                    "input_text": script_text,
                    "voice_id": voice_settings.get("voice_id", "en-US-1"),
                },
            }],
            "dimension": {
                "width": format_settings.get("width", 1080),
                "height": format_settings.get("height", 1920),
            },
        }
    elif p == "SYNTHESIA":
        payload = {
            "test": True,
            "title": voice_settings.get("title", "AI Video"),
            "description": script_text[:200],
            "visibility": "private",
            "slides": [{
                "avatar": voice_settings.get("avatar_id", "anna_costume1_cameraA"),
                "avatarSettings": {
                    "voice": voice_settings.get("voice_id", "en-US"),
                    "script": script_text,
                },
                "background": format_settings.get("background", "#ffffff"),
            }],
        }
    elif p == "RUNWAY":
        payload = {
            "promptText": script_text,
            "model": "gen3a_turbo",
            "duration": format_settings.get("duration_sec", 5),
            "ratio": format_settings.get("ratio", "768:1344"),
            "watermark": False,
        }
    else:
        # MOCK — used in tests and when real providers are not configured
        payload = {"mock": True, **base}

    return {
        "provider": p,
        "payload": payload,
        "requires_ai_label": True,  # all AI-generated content requires disclosure (§25 p.11)
    }


def extract_voice_atoms(voice_settings: dict) -> list[dict]:
    """
    Derive Voice-as-Atom entries from provider voice settings.
    Returns list of {atom_type, atom_value, confidence}.
    Atom types: VOICE_TONE, VOICE_PACE, VOICE_TIMBRE.
    """
    atoms = []

    tone = voice_settings.get("tone")
    if tone:
        atoms.append({"atom_type": "VOICE_TONE", "atom_value": tone.upper(), "confidence": 0.90})

    pace = voice_settings.get("pace")
    if pace:
        atoms.append({"atom_type": "VOICE_PACE", "atom_value": pace.upper(), "confidence": 0.90})

    timbre = voice_settings.get("timbre")
    if timbre:
        atoms.append({"atom_type": "VOICE_TIMBRE", "atom_value": timbre.upper(), "confidence": 0.85})

    # Derive from gender/style if explicit atoms not given
    if not tone and voice_settings.get("style"):
        style = voice_settings["style"].lower()
        if "warm" in style or "friendly" in style:
            atoms.append({"atom_type": "VOICE_TONE", "atom_value": "WARM", "confidence": 0.75})
        elif "authoritative" in style or "professional" in style:
            atoms.append({"atom_type": "VOICE_TONE", "atom_value": "AUTHORITATIVE", "confidence": 0.75})

    return atoms


def compute_ab_acceptance(
    ai_conversions: int,
    ai_sessions: int,
    live_conversions: int,
    live_sessions: int,
    mc_samples: int = _MC_SAMPLES,
) -> dict:
    """
    Monte Carlo Beta posterior: P(AI CVR >= live CVR).
    Both arms use uninformative Beta(1,1) prior.
    Returns {p_ai_gte_live, acceptance: PASS|FAIL|INSUFFICIENT_DATA, threshold}.
    PASS requires P >= 0.9 AND both arms have >= 50 sessions.
    """
    if ai_sessions < _MIN_SESSIONS_PER_ARM or live_sessions < _MIN_SESSIONS_PER_ARM:
        return {
            "p_ai_gte_live": None,
            "acceptance": "INSUFFICIENT_DATA",
            "threshold": _ACCEPTANCE_THRESHOLD,
            "reason": f"Need >= {_MIN_SESSIONS_PER_ARM} sessions per arm; "
                      f"got ai={ai_sessions}, live={live_sessions}",
        }

    # Beta posterior: alpha = conversions + 1 (prior 1), beta = non-conv + 1
    alpha_ai   = ai_conversions + 1
    beta_ai    = (ai_sessions - ai_conversions) + 1
    alpha_live = live_conversions + 1
    beta_live  = (live_sessions - live_conversions) + 1

    samples_ai   = _RNG.beta(alpha_ai, beta_ai, size=mc_samples)
    samples_live = _RNG.beta(alpha_live, beta_live, size=mc_samples)

    p_ai_gte_live = float(np.mean(samples_ai >= samples_live))

    acceptance = "PASS" if p_ai_gte_live >= _ACCEPTANCE_THRESHOLD else "FAIL"

    return {
        "p_ai_gte_live": round(p_ai_gte_live, 4),
        "acceptance": acceptance,
        "threshold": _ACCEPTANCE_THRESHOLD,
        "ai_cvr_mean": round(alpha_ai / (alpha_ai + beta_ai), 4),
        "live_cvr_mean": round(alpha_live / (alpha_live + beta_live), 4),
    }
