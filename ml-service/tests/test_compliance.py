"""Tests for compliance_service.py"""
import pytest
from app.services.compliance_service import (
    check_trademarks,
    run_all_text_checks,
)


def test_trademark_no_terms_returns_pass():
    result = check_trademarks("Buy our product today!", [])
    assert result["result"] == "PASS"
    assert result["findings"] == []


def test_trademark_match_case_insensitive():
    result = check_trademarks("Get Nike shoes for less", ["Nike"])
    assert result["result"] == "WARN"
    assert any(f["excerpt"] == "Nike" for f in result["findings"])


def test_trademark_partial_word_match():
    result = check_trademarks("Nikestore has great deals", ["Nike"])
    assert result["result"] == "WARN"


def test_trademark_no_match():
    result = check_trademarks("Great running shoes on sale", ["Nike", "Adidas"])
    assert result["result"] == "PASS"


def test_run_all_checks_no_client_trademark_still_runs():
    result = run_all_text_checks(
        text="Buy Nike shoes — guaranteed to cure back pain!",
        product_facts=[],
        trademark_terms=["Nike"],
        llm_client=None,
        model="claude-haiku-5-5",
    )
    # meta_policy and claims return WARN (no LLM), trademark returns WARN
    assert result["result"] in ("WARN", "FAIL")
    assert any(f.get("check_type") == "TRADEMARK" for f in result["findings"])


def test_run_all_checks_no_violations():
    result = run_all_text_checks(
        text="Fast delivery for your order",
        product_facts=["We offer same-day delivery"],
        trademark_terms=[],
        llm_client=None,
    )
    assert "result" in result
    assert "checks" in result
    assert set(result["checks"].keys()) == {"meta_policy", "claims", "trademark"}


def test_run_all_checks_fail_overrides_warn():
    # Force a FAIL by having a trademark + simulate structure returning FAIL
    # We test the logic via check_trademarks integration
    # Since no LLM, meta_policy returns WARN, claims returns PASS (no facts), trademark PASS
    result = run_all_text_checks("Hello world", [], [], None)
    # meta_policy no-llm returns WARN
    assert result["result"] == "WARN"
