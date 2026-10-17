"""
Persona Sandbox service — no FastAPI/Pydantic imports.
Vision §12.6, §22; arch §15.13.
"""
from __future__ import annotations

import math
from collections import Counter
from typing import Optional


def build_persona(sessions: list[dict], cluster_id: int, label: str) -> dict:
    """
    Builds a persona centroid from a list of sessions belonging to a cluster.

    sessions: list of {scroll_depth, session_duration_sec, device_type, geo,
                       utm_source, converted, bounced}
    Returns persona dict with aggregate stats.
    """
    if not sessions:
        return {
            "cluster_id": cluster_id,
            "label": label,
            "session_count": 0,
            "avg_scroll_depth": 0.0,
            "avg_session_duration_sec": 0.0,
            "conversion_rate": 0.0,
            "bounce_rate": 0.0,
            "top_utm_sources": [],
            "top_geo": None,
            "device_split": {},
        }

    n = len(sessions)
    scroll_depths = [float(s.get("scroll_depth", 0)) for s in sessions]
    durations = [float(s.get("session_duration_sec", 0)) for s in sessions]
    conversions = sum(1 for s in sessions if s.get("converted"))
    bounces = sum(1 for s in sessions if s.get("bounced"))

    utm_counter = Counter(s.get("utm_source", "direct") for s in sessions)
    geo_counter = Counter(s.get("geo", "unknown") for s in sessions)
    device_counter: Counter = Counter()
    for s in sessions:
        dt = s.get("device_type", "unknown")
        device_counter[dt] += 1

    top_utms = [src for src, _ in utm_counter.most_common(3)]
    top_geo = geo_counter.most_common(1)[0][0] if geo_counter else None
    device_split = {k: round(v / n, 3) for k, v in device_counter.items()}

    return {
        "cluster_id": cluster_id,
        "label": label,
        "session_count": n,
        "avg_scroll_depth": round(sum(scroll_depths) / n, 3),
        "avg_session_duration_sec": round(sum(durations) / n, 1),
        "conversion_rate": round(conversions / n, 4),
        "bounce_rate": round(bounces / n, 4),
        "top_utm_sources": top_utms,
        "top_geo": top_geo,
        "device_split": device_split,
    }


def score_session_against_persona(session: dict, persona: dict) -> float:
    """
    Returns similarity score [0, 1] between a session and a persona centroid.
    Uses weighted feature comparison.
    """
    score = 0.0
    total_weight = 0.0

    def _add(weight: float, similarity: float) -> None:
        nonlocal score, total_weight
        score += weight * max(0.0, min(1.0, similarity))
        total_weight += weight

    # scroll_depth similarity (Gaussian kernel)
    sess_scroll = float(session.get("scroll_depth", 0))
    persona_scroll = float(persona.get("avg_scroll_depth", 0))
    scroll_sim = math.exp(-((sess_scroll - persona_scroll) ** 2) / 0.2)
    _add(0.25, scroll_sim)

    # session duration similarity
    sess_dur = float(session.get("session_duration_sec", 0))
    persona_dur = float(persona.get("avg_session_duration_sec", 1))
    dur_ratio = min(sess_dur, persona_dur) / max(persona_dur, 1.0)
    _add(0.2, dur_ratio)

    # device type match
    sess_device = str(session.get("device_type", "")).lower()
    persona_split = persona.get("device_split", {})
    device_sim = persona_split.get(sess_device, 0.0)
    _add(0.2, device_sim)

    # geo match
    sess_geo = str(session.get("geo", "")).lower()
    persona_geo = str(persona.get("top_geo", "")).lower()
    _add(0.15, 1.0 if sess_geo and sess_geo == persona_geo else 0.0)

    # utm_source match
    sess_utm = str(session.get("utm_source", "")).lower()
    top_utms = [u.lower() for u in persona.get("top_utm_sources", [])]
    _add(0.2, 1.0 if sess_utm in top_utms else 0.0)

    if total_weight < 1e-9:
        return 0.0
    return round(score / total_weight, 4)
