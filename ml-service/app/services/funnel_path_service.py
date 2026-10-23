"""
Funnel Path Thompson Sampling (vision §19.18.1; arch §16.14).
Pure logic — zero FastAPI/Pydantic imports.

Paths are Thompson arms, rewarded on CALL_HELD normalised per 1000 PAGE_VIEW.
Single writer rule: FastAPI is sole writer of alpha/beta.
"""
from __future__ import annotations
import random
import math


def select_path(
    paths: list[dict],
    ticket_bucket: str | None = None,
) -> dict | None:
    """
    Thompson Sampling over funnel paths.
    TIERED_BY_TICKET overrides when ticket_bucket is known.
    """
    active = [p for p in paths if p.get("status") == "ACTIVE"]
    if not active:
        return None

    tiered = next((p for p in active if p.get("path_key") == "TIERED_BY_TICKET"), None)
    if tiered and ticket_bucket:
        return tiered

    samples = {
        p["path_id"]: random.betavariate(
            max(float(p.get("alpha", 1.0)), 1.0),
            max(float(p.get("beta",  1.0)), 1.0),
        )
        for p in active
        if p.get("path_key") != "TIERED_BY_TICKET"
    }
    if not samples:
        return active[0]

    best_id = max(samples, key=lambda k: samples[k])
    return next(p for p in active if p["path_id"] == best_id)


def check_path_b_engagement_gate(
    events: list[dict],
    gate_threshold: dict | None = None,
) -> dict:
    """
    Path B (MATERIALS_FIRST) engagement gate.
    Pass: MATERIAL_VIEW + (DATAROOM_OPEN OR VIDEO_PROGRESS >= 75).
    """
    defaults = {
        "require_material_view": True,
        "require_dataroom_or_video": True,
        "video_progress_threshold": 75,
    }
    gate = gate_threshold or defaults

    event_types = {e.get("event_type") for e in events}
    has_material_view = "MATERIAL_VIEW" in event_types
    has_dataroom      = "DATAROOM_OPEN" in event_types
    has_video_75      = any(
        e.get("event_type") == "VIDEO_PROGRESS" and
        int(e.get("payload", {}).get("progress_pct")
            or e.get("payload", {}).get("progress")
            or e.get("payload", {}).get("percent")
            or 0) >= gate["video_progress_threshold"]
        for e in events
    )

    material_ok   = has_material_view if gate["require_material_view"]         else True
    engagement_ok = (has_dataroom or has_video_75) if gate["require_dataroom_or_video"] else True
    gate_passed   = material_ok and engagement_ok

    return {
        "gate_passed":       gate_passed,
        "has_material_view": has_material_view,
        "has_dataroom":      has_dataroom,
        "has_video_75":      has_video_75,
        "missing": [
            k for k, v in {
                "MATERIAL_VIEW":        has_material_view,
                "DATAROOM_OR_VIDEO_75": (has_dataroom or has_video_75),
            }.items() if not v
        ],
    }


def resolve_tiered_path_variant(
    ticket_bucket: str,
    tiered_config: dict,
) -> dict:
    """Path C (TIERED_BY_TICKET): sub-variant by ticket bucket."""
    if ticket_bucket in ("GT250K", "100_250K"):
        return tiered_config.get("GTE250K", {"ghl_tag": "path_c_founder_call", "variant_key": "1on1_founder"})
    elif ticket_bucket in ("50_100K", "25_50K"):
        return tiered_config.get("GTE50K",  {"ghl_tag": "path_c_1on1_call",    "variant_key": "1on1_standard"})
    else:
        return tiered_config.get("LT50K",   {"ghl_tag": "path_c_webinar",      "variant_key": "webinar"})


def compute_path_metrics(
    reward_events: list[dict],
    page_views: dict,
    per_1000_lpv: bool = True,
) -> list[dict]:
    """CALL_HELD per 1000 LPV per path with 80% credible interval."""
    from collections import defaultdict
    path_held: dict = defaultdict(int)

    for ev in reward_events:
        pid = ev.get("path_id") or "null"
        if ev.get("is_quality_stage"):
            path_held[pid] += 1

    results = []
    for path_id, lpv in page_views.items():
        held  = path_held.get(path_id, 0)
        rate  = (held / max(lpv, 1)) * (1000 if per_1000_lpv else 1)
        alpha = held + 1
        beta  = (lpv - held) + 1
        mean  = alpha / (alpha + beta)
        z     = 1.282
        se    = math.sqrt(mean * (1 - mean) / max(lpv, 1))
        results.append({
            "path_id":         path_id,
            "held_calls":      held,
            "page_views":      lpv,
            "held_per_1k_lpv": round(rate, 2),
            "posterior_mean":  round(mean, 4),
            "ci_80_lower":     round(max(0.0, mean - z * se), 4),
            "ci_80_upper":     round(min(1.0, mean + z * se), 4),
        })
    results.sort(key=lambda x: -x["held_per_1k_lpv"])
    return results
