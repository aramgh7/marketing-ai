"""Tests for graveyard_service.py — Phase 6-5."""
import pytest
from app.services.graveyard_service import (
    classify_failure_mode,
    analyze_failure,
    generate_postmortem_summary,
    FAILURE_THRESHOLDS,
)

ATOMS_HOOK    = [{"atom_type": "hook", "label": "H"}, {"atom_type": "body", "label": "B"}]
ATOMS_LANDING = [{"atom_type": "cta",  "label": "C"}, {"atom_type": "body", "label": "B"}]
ATOMS_OFFER   = [{"atom_type": "offer","label": "O"}]
ATOMS_GENERIC = [{"atom_type": "video","label": "V"}]


class TestClassifyFailureMode:
    def test_insufficient_data_below_min_sessions(self):
        mode = classify_failure_mode(0.001, 0.005, 30, 0.02, ["hook"])
        assert mode == "insufficient_data"

    def test_hook_failure_low_ctr_with_hook_atom(self):
        mode = classify_failure_mode(0.02, 0.005, 100, 0.02, ["hook", "body"])
        assert mode == "hook_failure"

    def test_landing_failure_low_cvr_good_ctr_no_hook(self):
        mode = classify_failure_mode(0.002, 0.05, 100, 0.02, ["cta", "body"])
        assert mode == "landing_failure"

    def test_offer_weakness_very_low_cvr(self):
        mode = classify_failure_mode(0.001, None, 100, 0.02, ["offer"])
        assert mode in ("offer_weakness", "landing_failure", "general_underperformance")

    def test_general_underperformance(self):
        mode = classify_failure_mode(0.008, 0.02, 200, 0.02, ["video"])
        assert mode == "general_underperformance"

    def test_valid_return_values(self):
        valid = {
            "insufficient_data", "hook_failure", "landing_failure",
            "offer_weakness", "general_underperformance"
        }
        mode = classify_failure_mode(0.003, 0.008, 75, 0.02, ["hook"])
        assert mode in valid

    def test_no_ctr_with_sufficient_sessions(self):
        mode = classify_failure_mode(0.001, None, 200, 0.02, ["offer"])
        assert mode in {
            "offer_weakness", "general_underperformance", "landing_failure"
        }


class TestAnalyzeFailure:
    def test_returns_all_fields(self):
        result = analyze_failure(ATOMS_HOOK, 100, 0.005, 0.02, ctr=0.005)
        assert {"failure_mode", "cvr", "benchmark_cvr", "cvr_vs_benchmark",
                "sessions", "ctr", "weak_atom_types", "recommendation"} <= result.keys()

    def test_cvr_vs_benchmark_negative_when_below(self):
        result = analyze_failure(ATOMS_GENERIC, 100, 0.005, 0.02)
        assert result["cvr_vs_benchmark"] < 0

    def test_recommendation_not_empty(self):
        result = analyze_failure(ATOMS_HOOK, 100, 0.002, 0.02, ctr=0.005)
        assert len(result["recommendation"]) > 0

    def test_insufficient_data_small_sessions(self):
        result = analyze_failure(ATOMS_HOOK, 10, 0.001, 0.02)
        assert result["failure_mode"] == "insufficient_data"

    def test_zero_benchmark_handled(self):
        result = analyze_failure(ATOMS_GENERIC, 100, 0.005, 0.0)
        assert result["cvr_vs_benchmark"] == 0.0


class TestGeneratePostmortemSummary:
    def test_returns_markdown_string(self):
        analysis = {
            "failure_mode": "hook_failure",
            "cvr": 0.005,
            "benchmark_cvr": 0.02,
            "sessions": 120,
            "recommendation": "Replace hook.",
        }
        md = generate_postmortem_summary("creative-123", analysis, [])
        assert "creative-123" in md
        assert "#" in md

    def test_failure_mode_in_output(self):
        analysis = {
            "failure_mode": "offer_weakness",
            "cvr": 0.002,
            "benchmark_cvr": 0.02,
            "sessions": 200,
            "recommendation": "Improve offer.",
        }
        md = generate_postmortem_summary("c-456", analysis, ATOMS_OFFER)
        assert "offer_weakness" in md

    def test_top_atoms_in_output(self):
        analysis = {
            "failure_mode": "landing_failure",
            "cvr": 0.003,
            "benchmark_cvr": 0.02,
            "sessions": 80,
            "recommendation": "Rewrite CTA.",
        }
        top_atoms = [{"atom_type": "cta", "label": "CTA Winner"}]
        md = generate_postmortem_summary("c-789", analysis, top_atoms)
        assert "CTA Winner" in md

    def test_empty_top_atoms(self):
        analysis = {
            "failure_mode": "general_underperformance",
            "cvr": 0.007,
            "benchmark_cvr": 0.02,
            "sessions": 300,
            "recommendation": "Retire.",
        }
        md = generate_postmortem_summary("c-000", analysis, [])
        assert "N/A" in md
