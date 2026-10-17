"""Tests for ai_video_service — 9 unit tests."""
import pytest
from app.services.ai_video_service import (
    validate_provider,
    build_provider_payload,
    extract_voice_atoms,
    compute_ab_acceptance,
)


# ── validate_provider ─────────────────────────────────────────────────────────

def test_validate_provider_heygen():
    r = validate_provider("heygen")
    assert r["valid"] is True
    assert r["provider"] == "HEYGEN"


def test_validate_provider_mock():
    r = validate_provider("MOCK")
    assert r["valid"] is True


def test_validate_provider_unknown():
    r = validate_provider("UNKNOWN_VENDOR")
    assert r["valid"] is False
    assert "UNKNOWN_VENDOR" in r["error"]


# ── build_provider_payload ────────────────────────────────────────────────────

def test_provider_payload_heygen():
    result = build_provider_payload("HEYGEN", "Hello world", {"voice_id": "v1"}, {"width": 720, "height": 1280})
    assert result["provider"] == "HEYGEN"
    assert result["requires_ai_label"] is True
    assert "video_inputs" in result["payload"]


def test_provider_payload_mock():
    result = build_provider_payload("MOCK", "Test", {}, {})
    assert result["provider"] == "MOCK"
    assert result["payload"]["mock"] is True


# ── extract_voice_atoms ───────────────────────────────────────────────────────

def test_extract_voice_atoms_explicit():
    atoms = extract_voice_atoms({"tone": "warm", "pace": "fast", "timbre": "bright"})
    types = [a["atom_type"] for a in atoms]
    assert "VOICE_TONE" in types
    assert "VOICE_PACE" in types
    assert "VOICE_TIMBRE" in types


def test_extract_voice_atoms_empty():
    atoms = extract_voice_atoms({})
    assert atoms == []


def test_extract_voice_atoms_style_derivation():
    atoms = extract_voice_atoms({"style": "warm and friendly"})
    assert any(a["atom_type"] == "VOICE_TONE" and a["atom_value"] == "WARM" for a in atoms)


# ── compute_ab_acceptance ─────────────────────────────────────────────────────

def test_ab_acceptance_insufficient_data():
    r = compute_ab_acceptance(5, 10, 5, 10)
    assert r["acceptance"] == "INSUFFICIENT_DATA"
    assert r["p_ai_gte_live"] is None


def test_ab_acceptance_pass():
    # AI arm significantly better: 45/60 vs 20/60
    r = compute_ab_acceptance(45, 60, 20, 60)
    assert r["acceptance"] == "PASS"
    assert r["p_ai_gte_live"] >= 0.9


def test_ab_acceptance_fail():
    # AI arm significantly worse: 10/60 vs 40/60
    r = compute_ab_acceptance(10, 60, 40, 60)
    assert r["acceptance"] == "FAIL"
    assert r["p_ai_gte_live"] < 0.9
