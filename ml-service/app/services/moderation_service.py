"""
Compliance by Construction + Moderation Probe + Reviewer Persona (vision §19.18.4; arch §16.17).
Pure logic — zero FastAPI/Pydantic imports.
"""
from __future__ import annotations
import re


def compose_from_approved(
    slots: dict,
    approved_phrases: list[dict],
    context_filter: str | None = None,
) -> dict:
    """
    Fill content slots from approved phrases only.
    Unmatched text → new_phrases[] needing legal review.
    """
    approved_by_context: dict = {}
    for ap in approved_phrases:
        ctx = ap.get("context")
        approved_by_context.setdefault(ctx, []).append(ap.get("phrase", ""))
        approved_by_context.setdefault(None, []).append(ap.get("phrase", ""))

    available       = approved_by_context.get(context_filter, []) + approved_by_context.get(None, [])
    available_lower = {p.lower(): p for p in available if p}

    filled_slots = {}
    new_phrases: list[str] = []

    for slot_key, desired_text in slots.items():
        if not desired_text:
            filled_slots[slot_key] = ""
            continue

        sentences   = [s.strip() for s in re.split(r"[.!?]+", desired_text) if s.strip()]
        slot_parts  = []
        needs_review = []

        for sentence in sentences:
            matched = next(
                (ap for ap_lower, ap in available_lower.items()
                 if ap_lower in sentence.lower() or sentence.lower() in ap_lower),
                None,
            )
            if matched:
                slot_parts.append(matched)
            else:
                slot_parts.append(sentence)
                needs_review.append(sentence)

        filled_slots[slot_key] = ". ".join(slot_parts)
        new_phrases.extend(needs_review)

    total_chars = sum(len(s) for s in slots.values())
    coverage = max(0.0, min(1.0, 1.0 - (len(new_phrases) / max(total_chars, 1) * 10)))

    return {
        "filled_slots": filled_slots,
        "new_phrases":  new_phrases,
        "needs_legal":  len(new_phrases) > 0,
        "coverage":     round(coverage, 3),
    }


def score_with_persona(
    text: str,
    persona: dict,
    compliance_results: dict,
) -> dict:
    """
    META_REVIEWER persona: pre-verdict P(Meta rejection).
    Advisory only — never blocks a launch.
    """
    persona_key = persona.get("persona_key", "UNKNOWN")
    findings    = compliance_results.get("findings", [])

    fail_count = sum(1 for f in findings if f.get("action") == "FAIL")
    warn_count = sum(1 for f in findings if f.get("action") == "WARN")

    p_reject = 1.0 - (1.0 - 0.85) ** fail_count * (1.0 - 0.20) ** warn_count
    p_reject = min(0.99, p_reject)

    verdict = (
        "LIKELY_REJECTED" if p_reject > 0.7
        else ("NEEDS_REVIEW" if p_reject > 0.3 else "LIKELY_APPROVED")
    )

    return {
        "persona_key": persona_key,
        "p_reject":    round(p_reject, 4),
        "verdict":     verdict,
        "fail_count":  fail_count,
        "warn_count":  warn_count,
        "note":        "Advisory only — never blocks a launch; compare weekly vs moderation_results",
    }


def check_moderation_probe_needed(
    is_new_creative: bool,
    budget_usd_day:  float,
    skip_probe:      bool = False,
) -> dict:
    """Recommend $5-10/day probe before real budget for new creatives."""
    if skip_probe:
        return {"probe_recommended": False, "reason": "probe skipped by caller"}
    if is_new_creative and budget_usd_day > 10:
        return {
            "probe_recommended": True,
            "probe_budget_day":  min(10.0, budget_usd_day * 0.1),
            "reason":            "New creative + real budget → probe first",
        }
    return {"probe_recommended": False, "reason": "Existing creative or low budget"}
