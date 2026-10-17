"""Tests for voc_service.py (vision §9.2, arch §15.8)"""
import pytest

from app.services.voc_service import (
    redact_pii, classify_objection_keyword, build_extraction_prompt,
    parse_extraction_response, fallback_extract, CATEGORIES,
)


class TestRedactPii:
    def test_email_redacted(self):
        result = redact_pii("Contact us at user@example.com for info")
        assert "user@example.com" not in result
        assert "[EMAIL]" in result

    def test_phone_redacted(self):
        result = redact_pii("Call us at +7 (999) 123-45-67")
        assert "+7" not in result or "[PHONE]" in result

    def test_no_pii_unchanged(self):
        text = "This product is too expensive for our budget."
        assert redact_pii(text) == text

    def test_multiple_emails_redacted(self):
        result = redact_pii("From: a@b.com to c@d.com")
        assert "[EMAIL]" in result
        assert "@" not in result.replace("[EMAIL]", "")


class TestClassifyObjectionKeyword:
    def test_price_keyword(self):
        assert classify_objection_keyword("This is too expensive") == "PRICE"

    def test_trust_keyword(self):
        assert classify_objection_keyword("I need a guarantee before buying") == "TRUST"

    def test_competitor_keyword(self):
        assert classify_objection_keyword("I saw a better offer from a competitor") == "COMPETITOR"

    def test_unknown_returns_other(self):
        assert classify_objection_keyword("xyzzy quux") == "OTHER"

    def test_result_always_in_categories(self):
        texts = ["free shipping", "deliver fast", "how does it work", "need time to think"]
        for t in texts:
            assert classify_objection_keyword(t) in CATEGORIES


class TestBuildExtractionPrompt:
    def test_contains_categories(self):
        prompt = build_extraction_prompt(["too expensive"], niche="ecommerce")
        assert "PRICE" in prompt
        assert "TRUST" in prompt

    def test_niche_included(self):
        prompt = build_extraction_prompt(["question"], niche="SaaS")
        assert "SaaS" in prompt

    def test_truncates_at_50_quotes(self):
        quotes = [f"quote {i}" for i in range(100)]
        prompt = build_extraction_prompt(quotes)
        assert "quote 49" in prompt
        assert "quote 50" not in prompt


class TestParseExtractionResponse:
    def test_valid_json_parsed(self):
        raw = '[{"category":"PRICE","objection_text":"Too costly","representative_quote":"way too expensive","count":5}]'
        result = parse_extraction_response(raw)
        assert len(result) == 1
        assert result[0]["category"] == "PRICE"
        assert result[0]["count"] == 5

    def test_invalid_category_normalized_to_other(self):
        raw = '[{"category":"UNKNOWN","objection_text":"something","representative_quote":"q","count":1}]'
        result = parse_extraction_response(raw)
        assert result[0]["category"] == "OTHER"

    def test_no_json_returns_empty(self):
        result = parse_extraction_response("Sorry, I cannot do that.")
        assert result == []

    def test_malformed_json_returns_empty(self):
        result = parse_extraction_response("[{broken")
        assert result == []


class TestFallbackExtract:
    def test_buckets_by_category(self):
        quotes = ["too expensive", "need guarantee", "call us at +1 555 0100"]
        result = fallback_extract(quotes)
        cats = {r["category"] for r in result}
        assert "PRICE" in cats

    def test_all_results_in_categories(self):
        quotes = ["price too high", "I trust this brand", "unclear how it works"]
        result = fallback_extract(quotes)
        for r in result:
            assert r["category"] in CATEGORIES
