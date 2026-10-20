"""Roadmap 8-18: PII redaction and keyword objection tagging — pure logic."""
from app.services.pii_redaction_service import redact_pii, summarise_transcript, tag_objections


def test_redacts_email_phone_ssn_card_address_account():
    text = ("Reach me at john.doe@example.com or (415) 555-0100. SSN 123-45-6789, "
            "card 4111 1111 1111 1111, I live at 12 Market Street, routing number 021000021.")
    out, counts = redact_pii(text)
    assert "[EMAIL]" in out and "[PHONE]" in out and "[SSN]" in out and "[CARD]" in out
    assert "[ADDRESS]" in out and "[ACCOUNT]" in out
    assert "john.doe" not in out and "555-0100" not in out and "4111" not in out
    assert counts["email"] == 1 and counts["phone"] == 1 and counts["ssn"] == 1 and counts["card"] == 1


def test_amounts_and_plain_text_untouched():
    text = "I could invest 50,000 dollars next quarter, the deck was clear."
    out, counts = redact_pii(text)
    assert out == text and counts == {}


def test_empty_input():
    assert redact_pii("") == ("", {})
    assert tag_objections("") == []


def test_objection_tags_with_counts_and_evidence():
    text = ("Honestly it's not right now for me, maybe next quarter. I need to talk to my spouse first. "
            "And the minimum feels high, can I start smaller?")
    tags = tag_objections(text)
    names = [t["tag"] for t in tags]
    assert names[:1] == ["timing"] or "timing" in names
    assert "spouse_partner" in names and "minimum_ticket" in names
    timing = next(t for t in tags if t["tag"] == "timing")
    assert timing["hits"] >= 2 and "not right now" in timing["evidence"].lower()


def test_tags_sorted_by_hits_then_name():
    text = "too risky too risky, and the price is high"
    tags = tag_objections(text)
    assert tags[0]["tag"] == "risk" and tags[0]["hits"] == 2
    assert tags[1]["tag"] == "price"


def test_summary_prefers_objection_sentences():
    text = "Thanks for the intro. The lock-up worries me. We can talk next week. Also, what is the minimum?"
    summary = summarise_transcript(text, max_sentences=2)
    assert "lock-up" in summary and "minimum" in summary
    assert "Thanks for the intro" not in summary
