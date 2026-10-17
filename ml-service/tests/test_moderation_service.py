from app.services.moderation_service import (
    compose_from_approved,
    score_with_persona,
    check_moderation_probe_needed,
)

APPROVED = [
    {"phrase": "Open to accredited investors", "context": "SEC_506C"},
    {"phrase": "Investment involves risk", "context": None},
    {"phrase": "Past performance is not indicative of future results", "context": None},
]

META_REVIEWER = {
    "persona_key": "META_REVIEWER",
    "rulebases": [{"rulebase_key": "META_FINANCIAL"}, {"rulebase_key": "META_PERSONAL_ATTR"}],
    "traits": {"strictness": "high"},
}


def test_compose_from_approved_full():
    """Slots fully from approved phrases → needs_legal=False."""
    slots = {"headline": "Open to accredited investors", "disclaimer": "Investment involves risk"}
    result = compose_from_approved(slots, APPROVED)
    assert result["needs_legal"] is False
    assert result["new_phrases"] == []


def test_compose_from_approved_new():
    """New text not in approved → new_phrases not empty."""
    slots = {"headline": "Guaranteed returns await you"}
    result = compose_from_approved(slots, APPROVED)
    assert result["needs_legal"] is True
    assert len(result["new_phrases"]) > 0


def test_compose_empty_slot():
    """Empty slot → no new phrases."""
    result = compose_from_approved({"cta": ""}, APPROVED)
    assert result["filled_slots"]["cta"] == ""
    assert result["new_phrases"] == []


def test_persona_score_no_fails():
    """Zero FAILs → verdict=LIKELY_APPROVED."""
    result = score_with_persona("Open to accredited investors", META_REVIEWER, {"findings": []})
    assert result["verdict"] == "LIKELY_APPROVED"
    assert result["p_reject"] == 0.0


def test_persona_score_one_fail():
    """1 FAIL → p_reject > 0.7 → LIKELY_REJECTED."""
    findings = [{"action": "FAIL", "rule": "no_guaranteed_returns"}]
    result = score_with_persona("Guaranteed returns!", META_REVIEWER, {"findings": findings})
    assert result["p_reject"] > 0.7
    assert result["verdict"] == "LIKELY_REJECTED"


def test_persona_score_warn_only():
    """1 WARN only → NEEDS_REVIEW range."""
    findings = [{"action": "WARN", "rule": "vague_claim"}]
    result = score_with_persona("Strong returns possible", META_REVIEWER, {"findings": findings})
    assert result["verdict"] in ("NEEDS_REVIEW", "LIKELY_APPROVED")


def test_persona_advisory_only():
    """Score must carry advisory note."""
    result = score_with_persona("text", META_REVIEWER, {"findings": []})
    assert "Advisory only" in result["note"]
    assert result["persona_key"] == "META_REVIEWER"


def test_moderation_probe_recommended():
    """New creative + budget > $10 → probe recommended."""
    result = check_moderation_probe_needed(is_new_creative=True, budget_usd_day=100.0)
    assert result["probe_recommended"] is True
    assert result["probe_budget_day"] <= 10.0


def test_moderation_probe_not_needed_existing():
    """Existing creative → no probe."""
    result = check_moderation_probe_needed(is_new_creative=False, budget_usd_day=200.0)
    assert result["probe_recommended"] is False


def test_moderation_probe_skip():
    """skip_probe=True → no probe even for new creative."""
    result = check_moderation_probe_needed(is_new_creative=True, budget_usd_day=200.0, skip_probe=True)
    assert result["probe_recommended"] is False
