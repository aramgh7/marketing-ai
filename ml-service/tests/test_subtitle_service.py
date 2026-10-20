"""Roadmap 9-13: subtitles from Whisper segments — timing, wrapping, escaping, styles, files."""
import os

import pytest

from app.services.subtitle_service import (
    SUBTITLE_STYLES,
    escape_ass_text,
    format_ass_time,
    format_srt_time,
    segments_from_transcript,
    segments_to_ass,
    segments_to_srt,
    wrap_text,
    write_subtitle_file,
)

SEGS = [
    {"start": 0.0, "end": 2.5, "text": "Hello investors, welcome to the demo."},
    {"start": 2.5, "end": 6.0, "text": "This hydrogel keeps the root zone moist for weeks, even in a drought."},
    {"start": 6.0, "end": 7.0, "text": "   "},                       # empty → dropped
    {"start": 7.0, "end": 7.1, "text": "Book a call."},              # too short → extended
]


def test_srt_time_format():
    assert format_srt_time(0) == "00:00:00,000"
    assert format_srt_time(61.5) == "00:01:01,500"
    assert format_srt_time(3600 + 0.0015) == "01:00:00,002"
    assert format_srt_time(-3) == "00:00:00,000"


def test_ass_time_format():
    assert format_ass_time(0) == "0:00:00.00"
    assert format_ass_time(61.5) == "0:01:01.50"
    assert format_ass_time(3725.123) == "1:02:05.12"


def test_wrap_two_lines_max():
    lines = wrap_text("one two three four five six seven eight nine ten eleven twelve", max_chars=20, max_lines=2)
    assert len(lines) == 2
    assert " ".join(lines) == "one two three four five six seven eight nine ten eleven twelve"   # nothing dropped
    assert wrap_text("") == []
    assert wrap_text("short") == ["short"]


def test_srt_blocks_and_numbering():
    srt = segments_to_srt(SEGS)
    blocks = srt.strip().split("\n\n")
    assert len(blocks) == 3                      # blank segment dropped
    assert blocks[0].startswith("1\n00:00:00,000 --> 00:00:02,500\n")
    assert blocks[2].startswith("3\n00:00:07,000 --> 00:00:07,300")   # extended to MIN_SEGMENT_SEC


def test_srt_wraps_long_text():
    srt = segments_to_srt(SEGS)
    second = srt.strip().split("\n\n")[1].split("\n")
    assert len(second) == 4                      # index, timing, 2 text lines


def test_ass_header_and_events():
    ass = segments_to_ass(SEGS, style="white_bottom")
    assert "[Script Info]" in ass and "PlayResX: 1080" in ass and "PlayResY: 1920" in ass
    assert "[V4+ Styles]" in ass and "Style: Default,Arial," in ass
    assert ass.count("Dialogue: 0,") == 3
    # 37 chars → wrapped at ~32 into two ASS lines
    assert "Dialogue: 0,0:00:00.00,0:00:02.50,Default,,0,0,0,,Hello investors, welcome to the\\Ndemo." in ass


def test_ass_styles_alignment_and_border():
    assert "Alignment" in segments_to_ass(SEGS, "white_top")
    top = segments_to_ass(SEGS, "white_top").split("Style: Default,")[1].split("\n")[0].split(",")
    bottom = segments_to_ass(SEGS, "white_bottom").split("Style: Default,")[1].split("\n")[0].split(",")
    box = segments_to_ass(SEGS, "black_box").split("Style: Default,")[1].split("\n")[0].split(",")
    yellow = segments_to_ass(SEGS, "yellow_bold").split("Style: Default,")[1].split("\n")[0].split(",")
    # Format: Name,Fontname,Fontsize,Primary,Secondary,Outline,Back,Bold,...,BorderStyle(idx14),Outline,Shadow,Alignment(idx17)
    assert top[17] == "8" and bottom[17] == "2"
    assert box[14] == "3" and bottom[14] == "1"
    assert yellow[6] == "-1" and yellow[2] == "&H0000FFFF"


def test_ass_unknown_style_rejected():
    with pytest.raises(ValueError):
        segments_to_ass(SEGS, "comic_sans")
    assert "none" in SUBTITLE_STYLES


def test_ass_escaping_of_braces_and_newlines():
    assert escape_ass_text("a {b} c") == "a \\{b\\} c"
    assert escape_ass_text("line1\nline2") == "line1\\Nline2"
    ass = segments_to_ass([{"start": 0, "end": 1, "text": "{\\b1}bold{\\b0}"}])
    assert "{\\b1}" not in ass and "\\{" in ass


def test_ass_two_line_break():
    ass = segments_to_ass(SEGS)
    line = [l for l in ass.splitlines() if "hydrogel" in l][0]
    assert line.count("\\N") == 1


def test_segments_overlap_fixed_and_sorted():
    segs = [{"start": 5, "end": 9, "text": "second"}, {"start": 0, "end": 6, "text": "first"}]
    srt = segments_to_srt(segs)
    assert srt.index("first") < srt.index("second")
    assert "00:00:00,000 --> 00:00:05,000" in srt


def test_segments_from_transcript_even_timing():
    segs = segments_from_transcript("One two. Three four five. Six.", duration_sec=10)
    assert len(segs) == 3
    assert segs[0]["start"] == 0.0 and segs[-1]["end"] == 10.0
    assert all(s["end"] > s["start"] for s in segs)
    assert segments_from_transcript("", 10) == [] and segments_from_transcript("x", 0) == []


def test_write_subtitle_file(tmp_path):
    path = write_subtitle_file("1\n00:00:00,000 --> 00:00:01,000\nhi\n", "srt", str(tmp_path), name="t")
    assert path.endswith(os.path.join(str(tmp_path), "t.srt")) or path == os.path.join(str(tmp_path), "t.srt")
    with open(path, encoding="utf-8") as f:
        assert f.read().startswith("1\n")
    path2 = write_subtitle_file("x", ".ass", str(tmp_path))
    assert path2.endswith(".ass") and os.path.exists(path2)
