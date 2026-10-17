"""
Tests for securities compliance checks (vision §19.5 / arch §16.5).
Covers: regex rulebases, personal attribute check, approved phrases.
"""
from app.services.compliance_service import (
    check_text_against_rules,
    run_compliance_check,
    check_meta_personal_attr,
    is_from_approved_phrases,
)

META_FINANCIAL_RULES = [
    {
        "rule_id": "MF001",
        "severity": "FAIL",
        "pattern": "guaranteed (return|profit|income)",
        "description": "Guaranteed return",
        "suggestion": "Remove guarantee",
    },
    {
        "rule_id": "MF002",
        "severity": "FAIL",
        "pattern": "risk.?free investment",
        "description": "Risk-free claim",
        "suggestion": "Remove risk-free claim",
    },
    {
        "rule_id": "MF003",
        "severity": "WARN",
        "pattern": "invest|investment",
        "description": "Investment mention",
        "suggestion": "Add disclosure",
    },
]

META_PERSONAL_ATTR_RULES = [
    {
        "rule_id": "PA001",
        "severity": "FAIL",
        "pattern": "are you.{0,40}accredited",
        "description": "Viewer-targeting personal attr",
        "suggestion": "Rephrase as offer description",
    },
]


def test_guaranteed_return_fails():
    text = "You will earn guaranteed profit from our fund."
    result = check_text_against_rules(text, META_FINANCIAL_RULES)
    assert result["result"] == "FAIL"
    assert any(f["rule_id"] == "MF001" for f in result["findings"])


def test_clean_text_passes():
    text = "Open to verified accredited investors under Reg D 506(c)."
    result = check_text_against_rules(text, META_PERSONAL_ATTR_RULES)
    assert result["result"] == "PASS"
    assert result["findings"] == []


def test_personal_attr_viewer_question_fails():
    """'Are you an accredited investor?' must FAIL (§19.5: describe offer, not viewer)."""
    result = check_meta_personal_attr("Are you an accredited investor? Apply today.")
    assert result["result"] == "FAIL"
    assert any(f["rule_id"] == "PA001" for f in result["findings"])


def test_offer_description_passes():
    """'Open to accredited investors' describes offer, not viewer — must PASS."""
    result = check_meta_personal_attr("Open to accredited investors under Reg D 506(c).")
    assert result["result"] == "PASS"


def test_multi_rulebase_worst_wins():
    """FAIL in any rulebase → overall FAIL."""
    text = "Guaranteed profit! Open to investors."
    rulebases = {
        "META_FINANCIAL": META_FINANCIAL_RULES,
        "META_PERSONAL_ATTR": META_PERSONAL_ATTR_RULES,
    }
    result = run_compliance_check(text, rulebases)
    assert result["overall"] == "FAIL"
    assert result["per_rulebase"]["META_FINANCIAL"]["result"] == "FAIL"


def test_approved_phrase_match():
    phrases = ["Open to verified accredited investors", "Reg D 506(c) compliant offering"]
    result = is_from_approved_phrases(
        "Open to verified accredited investors under Reg D 506(c).", phrases
    )
    assert result["approved"] is True
    assert result["matched_phrase"] is not None
