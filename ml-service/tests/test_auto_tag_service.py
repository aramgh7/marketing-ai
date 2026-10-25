"""Tests for auto_tag_service — Phase 14."""
import json
import pytest
from app.services.auto_tag_service import (
    parse_tag_response,
    tag_correction_rate,
    L1_VALUES,
    L2_VALUES,
    L3_VALUES,
)


class TestParseTagResponse:
    def test_valid_json(self):
        raw = '{"tags_l1": ["founder_portrait"], "tags_l2": ["hero_background"], "tags_l3": ["professional"]}'
        result = parse_tag_response(raw)
        assert result["tags_l1"] == ["founder_portrait"]
        assert result["tags_l2"] == ["hero_background"]
        assert result["tags_l3"] == ["professional"]

    def test_filters_invalid_values(self):
        raw = '{"tags_l1": ["founder_portrait", "totally_invalid"], "tags_l2": [], "tags_l3": []}'
        result = parse_tag_response(raw)
        assert result["tags_l1"] == ["founder_portrait"]

    def test_markdown_fenced_json(self):
        raw = '```json\n{"tags_l1": ["chart_graph"], "tags_l2": [], "tags_l3": []}\n```'
        result = parse_tag_response(raw)
        assert result["tags_l1"] == ["chart_graph"]

    def test_empty_string_returns_empty(self):
        result = parse_tag_response("")
        assert result["tags_l1"] == []
        assert result["tags_l2"] == []
        assert result["tags_l3"] == []

    def test_non_json_returns_empty(self):
        result = parse_tag_response("not json at all")
        assert result["tags_l1"] == []

    def test_multiple_valid_tags_each_level(self):
        raw = '{"tags_l1": ["founder_portrait", "team_photo"], "tags_l2": ["hero_background", "hero_face"], "tags_l3": ["professional"]}'
        result = parse_tag_response(raw)
        assert len(result["tags_l1"]) == 2
        assert len(result["tags_l2"]) == 2

    def test_confidence_present(self):
        raw = '{"tags_l1": ["founder_portrait"], "tags_l2": [], "tags_l3": []}'
        result = parse_tag_response(raw)
        assert "confidence" in result

    def test_all_l1_values_accepted(self):
        raw_tags = L1_VALUES[:3]
        raw = json.dumps({"tags_l1": raw_tags, "tags_l2": [], "tags_l3": []})
        result = parse_tag_response(raw)
        assert result["tags_l1"] == raw_tags

    def test_no_lang_fenced_json(self):
        raw = '```\n{"tags_l1": ["document"], "tags_l2": [], "tags_l3": []}\n```'
        result = parse_tag_response(raw)
        assert result["tags_l1"] == ["document"]

    def test_all_three_levels_filtered(self):
        raw = json.dumps({
            "tags_l1": ["founder_portrait", "INVALID_L1"],
            "tags_l2": ["hero_background", "INVALID_L2"],
            "tags_l3": ["professional", "INVALID_L3"],
        })
        result = parse_tag_response(raw)
        assert result["tags_l1"] == ["founder_portrait"]
        assert result["tags_l2"] == ["hero_background"]
        assert result["tags_l3"] == ["professional"]


class TestTagCorrectionRate:
    def test_no_corrections(self):
        rows = [{"tag_level": "L1", "was_corrected": False}] * 5
        rates = tag_correction_rate(rows)
        assert rates["L1"] == 0.0

    def test_all_corrections(self):
        rows = [{"tag_level": "L2", "was_corrected": True}] * 4
        rates = tag_correction_rate(rows)
        assert rates["L2"] == 1.0

    def test_mixed_rate(self):
        rows = [
            {"tag_level": "L1", "was_corrected": True},
            {"tag_level": "L1", "was_corrected": False},
            {"tag_level": "L1", "was_corrected": False},
            {"tag_level": "L1", "was_corrected": False},
        ]
        rates = tag_correction_rate(rows)
        assert abs(rates["L1"] - 0.25) < 0.001

    def test_empty_rows(self):
        assert tag_correction_rate([]) == {}

    def test_missing_level_not_in_result(self):
        rows = [{"tag_level": "L1", "was_corrected": False}]
        rates = tag_correction_rate(rows)
        assert "L2" not in rates
        assert "L3" not in rates

    def test_multiple_levels(self):
        rows = [
            {"tag_level": "L1", "was_corrected": False},
            {"tag_level": "L2", "was_corrected": True},
            {"tag_level": "L3", "was_corrected": False},
        ]
        rates = tag_correction_rate(rows)
        assert rates["L1"] == 0.0
        assert rates["L2"] == 1.0
        assert rates["L3"] == 0.0
