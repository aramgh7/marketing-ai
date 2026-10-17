"""
Post-Call Intelligence (vision §19.18.5; arch §16.18).
Pure logic — zero FastAPI/Pydantic imports.

Routes follow-up arm based on call outcome + objection tags.
Computes per-objection conversion rates for brief and coordinator dashboard.
"""
from __future__ import annotations
import json
from collections import Counter

OBJECTION_ARM_MAP: dict[str, str] = {
    "ACCRED_UNCLEAR":       "arm_accred_education",
    "PRICE_CONCERN":        "arm_roi_calculator",
    "TIMING_NOT_RIGHT":     "arm_nurture_60d",
    "COMPETITOR_MENTION":   "arm_differentiator",
    "SPOUSE_APPROVAL":      "arm_couple_deck",
    "RISK_CONCERN":         "arm_risk_brief",
    "LIQUIDITY_CONCERN":    "arm_exit_options",
    "WANTS_MORE_INFO":      "arm_full_deck",
    "PORTFOLIO_ALLOCATION": "arm_allocation_model",
    "GEOGRAPHIC_CONCERN":   "arm_geo_compliance",
}

DEFAULT_ARM = "arm_standard_followup"


def route_followup_arm(objection_tags: list[str], call_outcome: str) -> dict:
    """
    Returns recommended follow-up arm for a completed call.
    NO_SHOW → recovery sequence (high priority).
    COLD → no arm.
    COMMITTED → wire instructions (urgent).
    Otherwise: first matched objection tag wins.
    """
    if call_outcome == "NO_SHOW":
        return {
            "arm":      "arm_noshow_recovery",
            "reason":   "No-show recovery sequence",
            "ghl_tag":  "post_call_noshow",
            "priority": "HIGH",
        }
    if call_outcome == "COLD":
        return {
            "arm":      None,
            "reason":   "Cold lead — no active follow-up",
            "ghl_tag":  None,
            "priority": "NONE",
        }
    if call_outcome == "COMMITTED":
        return {
            "arm":      "arm_wire_instructions",
            "reason":   "Committed — send wire instructions",
            "ghl_tag":  "post_call_committed",
            "priority": "URGENT",
        }

    for tag in objection_tags:
        if tag in OBJECTION_ARM_MAP:
            return {
                "arm":      OBJECTION_ARM_MAP[tag],
                "reason":   f"Objection: {tag}",
                "ghl_tag":  f"post_call_{tag.lower()}",
                "priority": "NORMAL",
            }

    return {
        "arm":      DEFAULT_ARM,
        "reason":   "No specific objection matched",
        "ghl_tag":  "post_call_standard",
        "priority": "NORMAL",
    }


def _parse_tags(raw) -> list[str]:
    if isinstance(raw, list):
        return raw
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except Exception:
            return []
    return []


def compute_objection_rates(call_notes: list[dict]) -> list[dict]:
    """
    Per-objection conversion rate (COMMITTED / total with that objection).
    Sorted by frequency desc.
    """
    tag_total: Counter = Counter()
    tag_converted: Counter = Counter()

    for note in call_notes:
        tags = _parse_tags(note.get("objection_tags") or [])
        converted = note.get("call_outcome") == "COMMITTED"
        for tag in tags:
            tag_total[tag] += 1
            if converted:
                tag_converted[tag] += 1

    return [
        {
            "objection_tag":   tag,
            "count_total":     total,
            "count_converted": tag_converted.get(tag, 0),
            "conversion_rate": round(tag_converted.get(tag, 0) / total, 4),
        }
        for tag, total in tag_total.most_common()
    ]


def summarise_call_batch(call_notes: list[dict], days: int = 30) -> dict:
    """
    Summary of call outcomes and top objections for coordinator dashboard.
    """
    outcomes: Counter = Counter(n.get("call_outcome") for n in call_notes)
    total = len(call_notes) or 1
    all_tags: list[str] = []
    for n in call_notes:
        all_tags.extend(_parse_tags(n.get("objection_tags") or []))

    top_objections = Counter(all_tags).most_common(5)
    return {
        "total_calls":    len(call_notes),
        "days":           days,
        "outcomes":       dict(outcomes),
        "commit_rate":    round(outcomes.get("COMMITTED", 0) / total, 4),
        "noshow_rate":    round(outcomes.get("NO_SHOW", 0) / total, 4),
        "top_objections": [{"tag": t, "count": c} for t, c in top_objections],
    }
