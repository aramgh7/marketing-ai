"""Tests for Competitive Intelligence — LLM response parsing (vision §9.1).

Tests cover pure JSON parsing helpers, stub fallbacks, and prompt formatting.
No I/O required.
"""
import json
import sys
from unittest.mock import MagicMock

# Mock deps
_mock_settings = MagicMock()
_mock_settings.internal_api_key = "test-key"
_mock_settings.anthropic_api_key = ""
_mock_config = MagicMock()
_mock_config.settings = _mock_settings
sys.modules.setdefault("app.config", _mock_config)
sys.modules.setdefault("anthropic", MagicMock())
import unittest.mock as _um
_fastapi = _um.MagicMock()
_fastapi.APIRouter.return_value = _um.MagicMock()
sys.modules.setdefault("fastapi", _fastapi)

import pytest

from app.routers.competitive import (
    _parse_json,
    _STUB_ATOMS,
    _STUB_COUNTER,
    _ATOM_EXTRACTION_PROMPT,
    _COUNTER_HYPOTHESIS_PROMPT,
)


class TestParseJson:

    def test_parses_valid_json(self):
        result = _parse_json('{"hook_type": "question", "tone": "urgent"}', {})
        assert result["hook_type"] == "question"

    def test_strips_markdown_fences(self):
        text = '```json\n{"hook_type": "statement"}\n```'
        result = _parse_json(text, {})
        assert result["hook_type"] == "statement"

    def test_returns_fallback_on_invalid(self):
        result = _parse_json("not valid json {{", {"fallback": True})
        assert result == {"fallback": True}

    def test_returns_fallback_on_none(self):
        result = _parse_json(None, {"fallback": True})
        assert result == {"fallback": True}

    def test_parses_json_array(self):
        text = '[{"hypothesis": "lead with pain point"}]'
        result = _parse_json(text, [])
        assert isinstance(result, list)
        assert result[0]["hypothesis"] == "lead with pain point"


class TestStubFallbacks:
    """Stubs must be valid and contain required fields."""

    def test_stub_atoms_has_required_fields(self):
        required = {"hook_type", "tone", "offer_type", "pain_points", "cta_type"}
        assert required.issubset(_STUB_ATOMS.keys())

    def test_stub_atoms_pain_points_is_list(self):
        assert isinstance(_STUB_ATOMS["pain_points"], list)

    def test_stub_counter_is_list(self):
        assert isinstance(_STUB_COUNTER, list)
        assert len(_STUB_COUNTER) >= 1

    def test_stub_counter_has_required_fields(self):
        required = {"hypothesis", "hook_suggestion", "offer_suggestion",
                    "differentiation", "confidence"}
        for item in _STUB_COUNTER:
            assert required.issubset(item.keys())

    def test_stub_counter_confidence_valid(self):
        valid = {"high", "medium", "low"}
        for item in _STUB_COUNTER:
            assert item["confidence"] in valid


class TestPromptFormatting:
    """Verify prompt templates format correctly with all inputs."""

    def test_atom_prompt_with_all_fields(self):
        prompt = _ATOM_EXTRACTION_PROMPT.format(
            title="Free Trial Offer",
            body="Transform your workflow today",
            page_name="Competitor Inc",
            run_days=21,
        )
        assert "Free Trial Offer" in prompt
        assert "Transform your workflow today" in prompt
        assert "Competitor Inc" in prompt
        assert "21" in prompt

    def test_atom_prompt_with_none_fields(self):
        # Should not raise when values are "(no title)" / "(no body)"
        prompt = _ATOM_EXTRACTION_PROMPT.format(
            title="(no title)",
            body="(no body)",
            page_name="unknown",
            run_days=0,
        )
        assert "Return a JSON object" in prompt

    def test_counter_prompt_with_atoms(self):
        atoms = json.dumps({"hook_type": "question", "tone": "urgent"})
        prompt = _COUNTER_HYPOTHESIS_PROMPT.format(
            run_days=14, atoms_json=atoms
        )
        assert "counter-hypotheses" in prompt
        assert "14" in prompt
        assert "hook_type" in prompt


class TestPixelPollutionDriftLogic:
    """Pure logic tests for drift detection thresholds (vision §6.8 Level 3)."""

    DRIFT_THRESHOLD = 0.70
    MIN_VOLUME = 20

    def _should_alert(self, quality_14d, baseline_30d, volume):
        if volume < self.MIN_VOLUME:
            return False
        if baseline_30d is None or baseline_30d == 0:
            return False
        return quality_14d < self.DRIFT_THRESHOLD * baseline_30d

    def test_no_alert_when_volume_below_min(self):
        assert not self._should_alert(0.01, 0.20, 5)

    def test_alert_when_severe_drop(self):
        """quality_rate drops from 20% to 5% — severe pollution."""
        assert self._should_alert(0.05, 0.20, 30)

    def test_no_alert_when_mild_drop(self):
        """quality_rate drops from 20% to 16% — within 70% threshold."""
        assert not self._should_alert(0.16, 0.20, 30)

    def test_boundary_exactly_at_threshold(self):
        """At exactly 70% of baseline → no alert (strict <)."""
        baseline = 0.20
        quality = baseline * 0.70  # exactly at threshold
        assert not self._should_alert(quality, baseline, 30)

    def test_boundary_just_below_threshold(self):
        """Just below 70% → alert fires."""
        baseline = 0.20
        quality = baseline * 0.699
        assert self._should_alert(quality, baseline, 30)

    def test_no_alert_when_baseline_is_zero(self):
        """Zero baseline (no historic data) → no alert to avoid false positives."""
        assert not self._should_alert(0.0, 0.0, 50)

    def test_no_alert_when_baseline_is_none(self):
        assert not self._should_alert(0.05, None, 50)

    def test_alert_with_min_volume_exactly(self):
        """Exactly min volume (20) with severe drop → alert fires."""
        assert self._should_alert(0.05, 0.20, 20)
