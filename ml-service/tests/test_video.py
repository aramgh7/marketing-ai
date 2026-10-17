"""
Tests for video template engine and variation generation (Phase 4-1, 4-2).

Fast unit tests only — no FFmpeg subprocess, no API calls, no DB.
Imports ONLY from app.services.video_service (no FastAPI dependency).
"""
import json
import pytest

from app.services.video_service import (
    build_ffmpeg_cmd,
    build_variations,
    parse_json_list,
    validate_montage_plan,
    FORMAT_SCALE,
    STUB_SCENES,
    STUB_PLAN,
)


# ──────────────────────────────────────────────────────────────────────────────
# Variation Generation (§5.2 Phase 4-2)
# ──────────────────────────────────────────────────────────────────────────────

class TestVariationMatrix:
    def test_3_hooks_1_body_3_ctas_gives_9_hook_variations(self):
        hooks = ["Hook A", "Hook B", "Hook C"]
        result = build_variations("t1", "f1", "url", hooks, ["Body"],
                                  ["CTA 1", "CTA 2", "CTA 3"], ["9:16"], add_subtitle=False)
        hook_vars = [v for v in result if v["variation_type"] == "HOOK_VARIATION"]
        assert len(hook_vars) == 9

    def test_2_hooks_1_body_2_ctas_gives_4_hook_variations(self):
        result = build_variations("t1", "f1", "url", ["H1", "H2"], ["Body"], ["C1", "C2"],
                                  ["9:16"], add_subtitle=False)
        hook_vars = [v for v in result if v["variation_type"] == "HOOK_VARIATION"]
        assert len(hook_vars) == 4

    def test_subtitle_variations_added_when_enabled(self):
        result = build_variations("t1", "f1", "url", ["H1", "H2"], ["Body"], ["C1", "C2"],
                                  ["9:16"], add_subtitle=True)
        overlay_vars = [v for v in result if v["variation_type"] == "OVERLAY_VARIATION"]
        assert len(overlay_vars) == 5  # 5 subtitle styles

    def test_subtitle_variations_not_added_when_disabled(self):
        result = build_variations("t1", "f1", "url", ["H1"], ["Body"], ["C1"],
                                  ["9:16"], add_subtitle=False)
        overlay_vars = [v for v in result if v["variation_type"] == "OVERLAY_VARIATION"]
        assert len(overlay_vars) == 0

    def test_multiple_formats_multiply_variations(self):
        result = build_variations("t1", "f1", "url", ["H1", "H2"], ["Body"], ["C1", "C2"],
                                  ["9:16", "4:5"], add_subtitle=False)
        hook_vars = [v for v in result if v["variation_type"] == "HOOK_VARIATION"]
        # 2 formats × 2 hooks × 1 body × 2 CTAs = 8
        assert len(hook_vars) == 8

    def test_variation_spec_has_required_fields(self):
        result = build_variations("t1", "f1", "url", ["Hook"], ["Body"], ["CTA"],
                                  ["9:16"], add_subtitle=False)
        v = result[0]
        assert v["variation_id"]
        assert v["variation_type"] == "HOOK_VARIATION"
        assert v["hook_text"] == "Hook"
        assert v["cta_text"] == "CTA"
        assert v["format"] == "9:16"
        assert "footage_url" in v["render_spec"]

    def test_empty_hooks_returns_no_variations(self):
        result = build_variations("t1", "f1", "url", [], ["Body"], ["CTA"],
                                  ["9:16"], add_subtitle=False)
        assert result == []

    def test_empty_ctas_returns_no_variations(self):
        result = build_variations("t1", "f1", "url", ["H1"], ["Body"], [],
                                  ["9:16"], add_subtitle=False)
        assert result == []

    def test_overlay_variations_created_per_format(self):
        """OVERLAY_VARIATION must be created for EACH format, not only the last one."""
        result = build_variations("t1", "f1", "url", ["H1"], ["Body"], ["C1"],
                                  ["9:16", "4:5"], add_subtitle=True)
        overlay_vars = [v for v in result if v["variation_type"] == "OVERLAY_VARIATION"]
        formats_in_overlays = {v["format"] for v in overlay_vars}
        # Both formats must appear in overlay variations (5 styles × 2 formats = 10)
        assert "9:16" in formats_in_overlays
        assert "4:5" in formats_in_overlays
        assert len(overlay_vars) == 10


# ──────────────────────────────────────────────────────────────────────────────
# Montage Plan Structure (§5.2)
# ──────────────────────────────────────────────────────────────────────────────

class TestMontagePlanStructure:
    def test_stub_plan_has_required_segments(self):
        segment_names = [s["segment"] for s in STUB_PLAN]
        assert "hook" in segment_names
        assert "cta" in segment_names

    def test_stub_plan_hook_duration_max_3s(self):
        hook = next(s for s in STUB_PLAN if s["segment"] == "hook")
        assert hook["duration_sec"] <= 3, "Hook must be ≤3 seconds (§5.2)"

    def test_stub_plan_total_duration_reasonable(self):
        total = sum(s["duration_sec"] for s in STUB_PLAN)
        assert 20 <= total <= 60, f"Unusual total duration: {total}s"

    def test_stub_scenes_has_all_recommended_for_values(self):
        values = {s["recommended_for"] for s in STUB_SCENES}
        assert "hook" in values
        assert "cta" in values

    def test_stub_plan_all_segments_have_required_keys(self):
        for seg in STUB_PLAN:
            assert "segment" in seg
            assert "duration_sec" in seg
            assert isinstance(seg["duration_sec"], (int, float))
            assert seg["duration_sec"] > 0

    def test_validate_stub_plan_returns_no_errors(self):
        errors = validate_montage_plan(STUB_PLAN)
        assert errors == [], f"Stub plan failed validation: {errors}"

    def test_validate_plan_missing_hook_returns_error(self):
        plan = [s for s in STUB_PLAN if s["segment"] != "hook"]
        errors = validate_montage_plan(plan)
        assert any("hook" in e.lower() for e in errors)

    def test_validate_plan_hook_over_3s_returns_error(self):
        plan = [{"segment": "hook", "duration_sec": 5},
                {"segment": "cta",  "duration_sec": 3}]
        errors = validate_montage_plan(plan)
        assert any("3s" in e or "Hook" in e for e in errors)


# ──────────────────────────────────────────────────────────────────────────────
# FFmpeg Command Building
# ──────────────────────────────────────────────────────────────────────────────

class TestFfmpegCmdBuilding:
    def test_cmd_starts_with_ffmpeg(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        assert cmd[0] == "ffmpeg"

    def test_cmd_includes_input_file(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        assert "/src.mp4" in cmd

    def test_cmd_output_is_last_arg(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        assert cmd[-1] == "/out.mp4"

    def test_cmd_includes_scale_9_16(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        vf = cmd[cmd.index("-vf") + 1]
        assert "1080:1920" in vf

    def test_cmd_includes_scale_4_5(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1350")
        vf = cmd[cmd.index("-vf") + 1]
        assert "1080:1350" in vf

    def test_cmd_is_list_not_string(self):
        """cmd must be a list so subprocess never invokes a shell."""
        plan_with_injection = list(STUB_PLAN)
        plan_with_injection[0] = dict(plan_with_injection[0],
                                      text_overlay="test' && rm -rf /")
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", plan_with_injection, "1080:1920")
        assert isinstance(cmd, list)

    def test_shell_injection_chars_escaped_in_vf(self):
        plan = [{"segment": "hook", "duration_sec": 3, "text_overlay": "buy now: 50% off"},
                {"segment": "cta",  "duration_sec": 3, "text_overlay": ""}]
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", plan, "1080:1920")
        vf = cmd[cmd.index("-vf") + 1]
        # colon must be escaped
        assert "\\:" in vf

    def test_format_scale_map_has_three_formats(self):
        assert "9:16" in FORMAT_SCALE
        assert "4:5"  in FORMAT_SCALE
        assert "1:1"  in FORMAT_SCALE

    def test_cmd_uses_libx264(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        assert "libx264" in cmd

    def test_cmd_duration_matches_plan(self):
        cmd = build_ffmpeg_cmd("/src.mp4", "/out.mp4", STUB_PLAN, "1080:1920")
        t_idx = cmd.index("-t")
        total_from_plan = sum(s.get("duration_sec", 5) for s in STUB_PLAN)
        assert float(cmd[t_idx + 1]) == pytest.approx(total_from_plan)


# ──────────────────────────────────────────────────────────────────────────────
# JSON parsing (for LLM responses)
# ──────────────────────────────────────────────────────────────────────────────

class TestParseJsonList:
    def test_parses_plain_json_array(self):
        raw = '[{"segment": "hook", "duration_sec": 3}]'
        result = parse_json_list(raw)
        assert result[0]["segment"] == "hook"

    def test_strips_markdown_fences_with_lang(self):
        raw = '```json\n[{"key": "val"}]\n```'
        result = parse_json_list(raw)
        assert result[0]["key"] == "val"

    def test_strips_markdown_fences_no_lang(self):
        raw = '```\n[{"key": "val"}]\n```'
        result = parse_json_list(raw)
        assert result[0]["key"] == "val"

    def test_ignores_text_before_array(self):
        raw = 'Here is the plan:\n[{"segment": "hook"}]'
        result = parse_json_list(raw)
        assert result[0]["segment"] == "hook"

    def test_raises_on_no_array(self):
        with pytest.raises(ValueError):
            parse_json_list("No JSON here at all.")

    def test_parses_multi_element_array(self):
        raw = json.dumps([{"a": 1}, {"a": 2}, {"a": 3}])
        result = parse_json_list(raw)
        assert len(result) == 3
