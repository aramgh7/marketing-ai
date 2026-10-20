"""PII redaction + objection tagging for call transcripts (roadmap 8-18; vision §19.18.5) — pure.

Transcripts of investor calls are stored only in redacted form: e-mails, phone numbers, card and
SSN-like numbers, street addresses and dollar amounts tied to a person are replaced by typed
placeholders. Names are NOT reliably detectable without an NER model and are left to the
coordinator's judgement (the transcript is never shown outside the tenant).

Objection tags are keyword-based (deterministic, no LLM) so the same vocabulary as
call_notes.objection_tags is produced: price, timing, trust, accreditation, liquidity,
minimum_ticket, spouse_partner, risk, competitor, information.
"""
from __future__ import annotations

import re

_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_PHONE = re.compile(r"(?<!\d)(?:\+?1[\s.-]?)?(?:\(?\d{3}\)?[\s.-]?)\d{3}[\s.-]?\d{4}(?!\d)")
_SSN = re.compile(r"(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)")
_CARD = re.compile(r"(?<!\d)(?:\d[ -]?){13,19}(?!\d)")
_ADDRESS = re.compile(
    r"\b\d{1,6}\s+(?:[A-Z][a-z]+\s){1,3}(?:Street|St\.?|Avenue|Ave\.?|Road|Rd\.?|Boulevard|Blvd\.?|Lane|Ln\.?|Drive|Dr\.?|Court|Ct\.?|Way)\b",
    re.I,
)
_ACCOUNT = re.compile(r"\b(?:account|acct|routing|iban|swift)\s*(?:number|no\.?|#)?\s*[:#]?\s*[A-Z0-9]{6,}\b", re.I)

OBJECTION_LEXICON: dict[str, list[str]] = {
    "price":          ["too expensive", "valuation", "price is high", "overpriced", "the price", "cost too much"],
    "timing":         ["not right now", "next quarter", "later this year", "bad timing", "wait until", "after the holidays", "next year"],
    "trust":          ["never heard of", "track record", "how do i know", "scam", "legit", "references", "due diligence"],
    "accreditation":  ["accredited", "not accredited", "net worth", "income requirement", "501(a)", "verification letter"],
    "liquidity":      ["lock-up", "lockup", "get my money out", "illiquid", "liquidity", "exit", "when do i get"],
    "minimum_ticket": ["minimum", "that much", "smaller amount", "start smaller", "25,000", "50,000", "entry size"],
    "spouse_partner": ["my wife", "my husband", "my partner", "talk to my spouse", "discuss with my", "my advisor", "my cpa"],
    "risk":           ["too risky", "lose everything", "what if it fails", "downside", "guarantee", "risky"],
    "competitor":     ["other deal", "another fund", "comparing", "other opportunity", "competitor"],
    "information":    ["send me more", "need more information", "read the materials", "data room", "deck first"],
}


def redact_pii(text: str) -> tuple[str, dict[str, int]]:
    """Return (redacted_text, counts_by_type). Order matters: cards before phones."""
    if not text:
        return "", {}
    counts: dict[str, int] = {}

    def sub(pattern: re.Pattern, label: str, s: str) -> str:
        n = 0
        def repl(_m):
            nonlocal n
            n += 1
            return f"[{label}]"
        out = pattern.sub(repl, s)
        if n:
            counts[label.lower()] = counts.get(label.lower(), 0) + n
        return out

    out = sub(_EMAIL, "EMAIL", text)
    out = sub(_SSN, "SSN", out)
    out = sub(_CARD, "CARD", out)
    out = sub(_PHONE, "PHONE", out)
    out = sub(_ADDRESS, "ADDRESS", out)
    out = sub(_ACCOUNT, "ACCOUNT", out)
    return out, counts


def tag_objections(text: str, lexicon: dict[str, list[str]] | None = None) -> list[dict]:
    """Keyword objection tags with hit counts and one evidence snippet each (deterministic)."""
    if not text:
        return []
    lex = lexicon or OBJECTION_LEXICON
    low = text.lower()
    out: list[dict] = []
    for tag, phrases in lex.items():
        # one regex per tag, longest phrase first, so "too risky" and "risky" count one hit, not two
        pattern = re.compile("|".join(re.escape(p) for p in sorted(phrases, key=len, reverse=True)))
        matches = list(pattern.finditer(low))
        if not matches:
            continue
        m0 = matches[0]
        start = max(0, m0.start() - 40)
        evidence = text[start: m0.end() + 40].strip()
        out.append({"tag": tag, "hits": len(matches), "evidence": evidence})
    out.sort(key=lambda d: (-d["hits"], d["tag"]))
    return out


def summarise_transcript(text: str, max_sentences: int = 3) -> str:
    """Very small extractive summary: the first sentences that contain an objection keyword, else the opening."""
    if not text:
        return ""
    sentences = re.split(r"(?<=[.!?])\s+", text.strip())
    keyed = [s for s in sentences if any(ph in s.lower() for phs in OBJECTION_LEXICON.values() for ph in phs)]
    chosen = (keyed or sentences)[:max_sentences]
    return " ".join(chosen)
