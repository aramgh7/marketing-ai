"""Roadmap 1-2: EDIT_PACE and MUSIC atoms — pure parsers and labels (no ffmpeg needed)."""
import asyncio
import sys
from unittest.mock import MagicMock

from app.services.media_atoms_service import (
    cuts_per_minute,
    edit_pace_label,
    media_atoms,
    music_label,
    parse_scene_timestamps,
    parse_silence_share,
    parse_volume,
)

SHOWINFO = """
[Parsed_showinfo_1 @ 0x1] n:   0 pts:  12012 pts_time:0.4004  pos: 1 fmt:yuv420p
[Parsed_showinfo_1 @ 0x1] n:   1 pts:  90090 pts_time:3.003   pos: 2 fmt:yuv420p
[Parsed_showinfo_1 @ 0x1] n:   2 pts: 150150 pts_time:5.005   pos: 3 fmt:yuv420p
"""
VOLUME = """
[Parsed_volumedetect_0 @ 0x2] mean_volume: -18.3 dB
[Parsed_volumedetect_0 @ 0x2] max_volume: -0.5 dB
[silencedetect @ 0x3] silence_start: 2.0
[silencedetect @ 0x3] silence_end: 3.0 | silence_duration: 1.0
[silencedetect @ 0x3] silence_start: 9.5
"""


def test_parse_scene_timestamps():
    assert parse_scene_timestamps(SHOWINFO) == [0.4004, 3.003, 5.005]
    assert parse_scene_timestamps("") == []


def test_edit_pace_labels():
    assert edit_pace_label(0, 30) == "static"
    assert edit_pace_label(2, 30) == "slow"        # 4 cpm
    assert edit_pace_label(6, 30) == "medium"      # 12 cpm
    assert edit_pace_label(15, 30) == "fast"       # 30 cpm
    assert edit_pace_label(5, 0) is None and edit_pace_label(5, None) is None
    assert cuts_per_minute(6, 30) == 12.0


def test_parse_volume_and_silence_share():
    assert parse_volume(VOLUME) == (-18.3, -0.5)
    # silence 2.0–3.0 (1 s) + 9.5–end(10 s) = 1.5 s of 10 s
    assert parse_silence_share(VOLUME, 10.0) == 0.15
    assert parse_silence_share(VOLUME, None) is None


def test_music_labels():
    assert music_label(False, None, None) == "none"
    assert music_label(True, -50.0, 0.0) == "none"          # effectively silent track
    assert music_label(True, -18.0, 0.05) == "dominant"     # continuous bed
    assert music_label(True, -18.0, 0.3) == "background"    # speech with gaps
    assert music_label(True, -18.0, 0.7) == "none"          # mostly silence
    assert music_label(True, None, None) == "background"


def test_media_atoms_shape():
    atoms = media_atoms(cuts=6, duration_sec=30, has_audio=True, mean_volume_db=-18.0, silence_share=0.05)
    by_type = {a["type"]: a for a in atoms}
    assert by_type["EDIT_PACE"]["value"] == "medium" and by_type["EDIT_PACE"]["extracted_by"] == "ffmpeg"
    assert by_type["MUSIC"]["value"] == "dominant"
    assert by_type["EDIT_PACE"]["meta"]["cuts_per_minute"] == 12.0


def test_creative_dna_merges_media_atoms_without_llm():
    # llm_client is never called for a VIDEO without thumbnail/transcript
    sys.modules.setdefault("app.services.llm_client", MagicMock())
    from app.models import creative_dna
    atoms = asyncio.run(creative_dna.extract(
        media_type="VIDEO", file_url="https://cdn.example.com/ad.mp4", thumbnail_url=None,
        transcript=None, duration_sec=28.0,
        media_atoms=media_atoms(3, 28.0, True, -20.0, 0.2)))
    types = [a["type"] for a in atoms]
    assert types[:1] == ["DURATION"] and "EDIT_PACE" in types and "MUSIC" in types
