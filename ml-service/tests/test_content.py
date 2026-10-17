"""Tests for content_service.py"""
import pytest
from app.services.content_service import (
    classify_rlha_delta,
    compute_match_score,
    generate_content,
)


def test_generate_content_stub_returns_all_slots():
    result = generate_content(
        block_type="hero",
        slots=["headline", "subheadline", "cta"],
        product_facts=["Fast delivery"],
        audience="SMBs",
        hook_text="Save time",
        best_atoms=[],
        rlha_few_shots=[],
        style_profile="",
        llm_client=None,
        model="claude-haiku-5-5",
    )
    assert result["stub"] is True
    assert set(result["slots"].keys()) == {"headline", "subheadline", "cta"}


def test_generate_content_stub_model_version():
    result = generate_content("hero", ["h1"], [], "", "", [], [], "", None)
    assert result["model_version"] == "stub"
    assert result["generation_id"].startswith("stub_")


def test_compute_match_score_stub_no_client():
    result = compute_match_score("Buy now", "Get started", "Free trial", None)
    assert result["stub"] is True
    assert result["score"] is None


def test_compute_match_score_stub_missing_hook():
    result = compute_match_score("", "headline", "offer", None)
    assert result["stub"] is True


def test_classify_rlha_delta_no_client_returns_empty():
    tags = classify_rlha_delta("original text", "approved text", None)
    assert tags == []


def test_generate_content_empty_slots():
    result = generate_content("hero", [], [], "", "", [], [], "", None)
    assert result["slots"] == {}
    assert result["stub"] is True
