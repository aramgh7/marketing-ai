"""Roadmap 9-15: music bed — licence validation, track choice, MUSIC atom, render option."""
import pytest

from app.services.music_service import (
    DEFAULT_GAIN_DB,
    bpm_bucket,
    choose_track,
    music_atom_for_track,
    render_music_option,
    validate_track,
)

TRACKS = [
    {"id": "1", "url": "https://cdn/x/calm.mp3", "title": "Calm", "licence": "royalty_free", "bpm": 72, "mood": "calm", "duration_sec": 120},
    {"id": "2", "url": "https://cdn/x/up.mp3", "title": "Upbeat", "licence": "licensed", "bpm": 128, "mood": "upbeat", "duration_sec": 45},
    {"id": "3", "url": "https://cdn/x/corp.mp3", "title": "Corp", "licence": "owned", "bpm": 100, "mood": "Corporate", "duration_sec": 200},
    {"id": "4", "url": "https://cdn/x/pirate.mp3", "title": "Unlicensed", "licence": "found_online", "bpm": 100, "mood": "calm"},
    {"id": "5", "url": "", "title": "No url", "licence": "owned"},
]


def test_bpm_buckets():
    assert bpm_bucket(None) == "UNKNOWN" and bpm_bucket("x") == "UNKNOWN" and bpm_bucket(0) == "UNKNOWN"
    assert bpm_bucket(72) == "SLOW" and bpm_bucket(90) == "MID" and bpm_bucket(120) == "MID" and bpm_bucket(121) == "FAST"


def test_validate_track_licence_and_fields():
    assert validate_track(TRACKS[0]) == []
    assert any("licence" in e for e in validate_track(TRACKS[3]))
    assert any("url" in e for e in validate_track(TRACKS[4]))
    assert any("bpm" in e for e in validate_track({"url": "u", "licence": "owned", "bpm": -5}))
    assert any("duration_sec" in e for e in validate_track({"url": "u", "licence": "owned", "duration_sec": "long"}))


def test_choose_track_prefers_mood_then_bpm_then_duration():
    assert choose_track(TRACKS, mood="UPBEAT")["id"] == "2"
    assert choose_track(TRACKS, mood="corporate")["id"] == "3"
    assert choose_track(TRACKS, bpm_range=(60, 80))["id"] == "1"
    assert choose_track(TRACKS, min_duration_sec=150)["id"] == "3"
    # no criteria → lowest id among licensed tracks; unlicensed never chosen
    assert choose_track(TRACKS)["id"] == "1"
    assert choose_track([TRACKS[3], TRACKS[4]]) is None
    assert choose_track([]) is None


def test_choose_track_mood_match_beats_bpm_match():
    chosen = choose_track(TRACKS, mood="calm", bpm_range=(120, 140))
    assert chosen["id"] == "1"


def test_music_atom_for_track():
    atom = music_atom_for_track(TRACKS[1])
    assert atom == {"atom_type": "MUSIC", "atom_value": "upbeat_FAST", "confidence": 1.0,
                    "extracted_by": "music-library", "track_id": "2"}
    assert music_atom_for_track({"url": "u", "licence": "owned"})["atom_value"] == "unspecified_UNKNOWN"


def test_render_music_option_defaults_and_rejection():
    opt = render_music_option(TRACKS[0])
    assert opt == {"url": TRACKS[0]["url"], "gain_db": DEFAULT_GAIN_DB, "duck": True, "track_id": "1"}
    assert render_music_option(TRACKS[2], gain_db=-6, duck=False)["gain_db"] == -6.0
    with pytest.raises(ValueError):
        render_music_option(TRACKS[3])
