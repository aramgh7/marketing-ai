"""
Investor Archetype Classifier (vision §19.18.6; arch §16.19).
Pure logic — zero FastAPI/Pydantic imports.

Rule-based until sufficient labelled data for ML.
Seeded labels: COMMITTED_QUICK / RESEARCHER / FENCE_SITTER / HIGH_VALUE_QUIET / DISQUALIFIED / UNKNOWN
"""
from __future__ import annotations


def classify_archetype(
    ticket_bucket:     str | None,
    self_accredited:   bool | None,
    time_to_form_h:    float | None,
    material_views:    int,
    dataroom_opens:    int,
    call_outcome:      str | None,
    objection_tags:    list[str],
    noshow_risk_tier:  str | None,
    touch_count:       int,
) -> dict:
    """
    Classify lead into investor archetype.
    Returns: {archetype, confidence, scores, signals}
    """
    signals: list[str] = []
    scores: dict[str, float] = {
        "COMMITTED_QUICK":  0.0,
        "RESEARCHER":       0.0,
        "FENCE_SITTER":     0.0,
        "HIGH_VALUE_QUIET": 0.0,
        "DISQUALIFIED":     0.0,
    }

    # DISQUALIFIED
    if self_accredited is False:
        scores["DISQUALIFIED"] += 0.7
        signals.append("NOT_ACCREDITED")
    if ticket_bucket == "LT25K":
        scores["DISQUALIFIED"] += 0.3
        signals.append("LOW_TICKET")

    # COMMITTED_QUICK
    if call_outcome == "COMMITTED":
        scores["COMMITTED_QUICK"] += 0.8
        signals.append("COMMITTED_ON_CALL")
    if time_to_form_h is not None and time_to_form_h < 3:
        scores["COMMITTED_QUICK"] += 0.3
        signals.append("FAST_FORM_SUBMIT")
    if ticket_bucket in ("GT250K", "100_250K") and self_accredited:
        scores["COMMITTED_QUICK"] += 0.3
        signals.append("HIGH_TICKET_ACCREDITED")

    # RESEARCHER
    if material_views >= 3:
        scores["RESEARCHER"] += 0.4
        signals.append("MULTI_MATERIAL_VIEW")
    if dataroom_opens >= 1:
        scores["RESEARCHER"] += 0.3
        signals.append("DATAROOM_OPENED")
    if time_to_form_h is not None and time_to_form_h > 24:
        scores["RESEARCHER"] += 0.2
        signals.append("SLOW_FORM_SUBMIT")
    if touch_count >= 5:
        scores["RESEARCHER"] += 0.2
        signals.append("HIGH_TOUCH_COUNT")

    # FENCE_SITTER
    fence_objections = {"TIMING_NOT_RIGHT", "PRICE_CONCERN", "SPOUSE_APPROVAL"}
    matched_fence = fence_objections & set(objection_tags)
    if matched_fence:
        scores["FENCE_SITTER"] += 0.4 * len(matched_fence)
        signals.extend(sorted(matched_fence))
    if touch_count >= 3 and call_outcome in (None, "INTERESTED_FOLLOW"):
        scores["FENCE_SITTER"] += 0.3
        signals.append("REPEATED_TOUCHES_NO_COMMIT")
    if noshow_risk_tier == "HIGH":
        scores["FENCE_SITTER"] += 0.2
        signals.append("HIGH_NOSHOW_RISK")

    # HIGH_VALUE_QUIET
    if ticket_bucket in ("GT250K", "100_250K") and material_views == 0 and touch_count <= 2:
        scores["HIGH_VALUE_QUIET"] += 0.6
        signals.append("HIGH_TICKET_LOW_ENGAGEMENT")
    if ticket_bucket == "GT250K" and not objection_tags:
        scores["HIGH_VALUE_QUIET"] += 0.3
        signals.append("NO_OBJECTIONS_HIGH_TICKET")

    best = max(scores, key=lambda k: scores[k])
    best_score = scores[best]

    if best_score < 0.3:
        archetype, confidence = "UNKNOWN", 0.3
    else:
        archetype  = best
        confidence = min(1.0, best_score)

    return {
        "archetype":  archetype,
        "confidence": round(confidence, 4),
        "scores":     {k: round(v, 4) for k, v in scores.items()},
        "signals":    list(dict.fromkeys(signals)),  # dedup, preserve first order
    }


def archetype_follow_up_strategy(archetype: str) -> dict:
    """
    Recommended follow-up strategy per archetype.
    """
    strategies: dict[str, dict] = {
        "COMMITTED_QUICK": {
            "priority":   "URGENT",
            "action":     "Send wire instructions + schedule confirmation call",
            "ghl_tag":    "archetype_committed_quick",
            "sla_hours":  2,
        },
        "RESEARCHER": {
            "priority":   "HIGH",
            "action":     "Send full data room + schedule Q&A call",
            "ghl_tag":    "archetype_researcher",
            "sla_hours":  12,
        },
        "FENCE_SITTER": {
            "priority":   "NORMAL",
            "action":     "Targeted objection-handling sequence",
            "ghl_tag":    "archetype_fence_sitter",
            "sla_hours":  24,
        },
        "HIGH_VALUE_QUIET": {
            "priority":   "HIGH",
            "action":     "Founder personal outreach",
            "ghl_tag":    "archetype_high_value_quiet",
            "sla_hours":  6,
        },
        "DISQUALIFIED": {
            "priority":   "LOW",
            "action":     "Remove from active sequence; add to waitlist if Reg CF opens",
            "ghl_tag":    "archetype_disqualified",
            "sla_hours":  None,
        },
        "UNKNOWN": {
            "priority":   "NORMAL",
            "action":     "Standard nurture sequence",
            "ghl_tag":    "archetype_unknown",
            "sla_hours":  48,
        },
    }
    return strategies.get(archetype, strategies["UNKNOWN"])
