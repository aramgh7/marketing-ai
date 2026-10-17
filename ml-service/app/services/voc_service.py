"""
Voice of Customer — pure logic, no FastAPI/Pydantic imports.
Clusters customer quotes into objection taxonomy (vision §9.2, arch §15.8).
"""
from __future__ import annotations

import re
import json
from typing import Optional

CATEGORIES = [
    "PRICE", "TRUST", "UNDERSTANDING", "URGENCY",
    "FIT", "PROCESS", "COMPETITOR", "OTHER",
]

CATEGORY_KEYWORDS: dict[str, list[str]] = {
    "PRICE":         ["цена", "дорого", "стоимость", "бюджет", "expensive", "price", "cost", "afford", "cheap"],
    "TRUST":         ["доверие", "отзыв", "гарантия", "риск", "trust", "guarantee", "review", "risk", "proof", "legit"],
    "UNDERSTANDING": ["непонятно", "как работает", "сложно", "объясните", "confus", "understand", "how does", "what is"],
    "URGENCY":       ["подумаю", "позже", "не сейчас", "later", "think about", "not now", "wait"],
    "FIT":           ["не подходит", "не для нас", "другой", "not for us", "doesn't fit", "alternative"],
    "PROCESS":       ["доставка", "сроки", "процесс", "оформление", "shipping", "timeline", "process", "how to order"],
    "COMPETITOR":    ["конкурент", "другая компания", "видел дешевле", "competitor", "better offer"],
}


def redact_pii(text: str) -> str:
    """Remove common PII patterns (email, phone) before storage."""
    text = re.sub(r'\b[\w.+-]+@[\w-]+\.\w{2,}\b', '[EMAIL]', text)
    text = re.sub(r'(\+?\d[\d\s\-().]{7,}\d)', '[PHONE]', text)
    return text


def classify_objection_keyword(text: str) -> str:
    """Keyword-based fallback classifier. Returns a CATEGORIES value."""
    text_lower = text.lower()
    scores: dict[str, int] = {cat: 0 for cat in CATEGORIES}
    for cat, keywords in CATEGORY_KEYWORDS.items():
        for kw in keywords:
            if kw.lower() in text_lower:
                scores[cat] += 1
    best = max(scores, key=lambda c: scores[c])
    return best if scores[best] > 0 else "OTHER"


def build_extraction_prompt(quotes: list[str], niche: Optional[str] = None) -> str:
    """Build LLM prompt for objection extraction and clustering."""
    niche_ctx = f"Ниша клиента: {niche}. " if niche else ""
    quotes_text = "\n".join(f"- {q}" for q in quotes[:50])
    return (
        f"{niche_ctx}Ниже — цитаты клиентов (отзывы, чаты поддержки, звонки). "
        "Извлеки и сгруппируй возражения по категориям.\n\n"
        "Категории: PRICE (цена/бюджет), TRUST (доверие/гарантии), "
        "UNDERSTANDING (непонимание продукта), URGENCY (откладывание решения), "
        "FIT (продукт не подходит), PROCESS (процесс покупки/доставки), "
        "COMPETITOR (упоминание конкурентов), OTHER.\n\n"
        f"Цитаты:\n{quotes_text}\n\n"
        "Верни JSON-массив объектов (только JSON, без пояснений):\n"
        '[{"category": "...", "objection_text": "краткое описание", '
        '"representative_quote": "лучшая цитата", "count": N}]'
    )


def parse_extraction_response(response_text: str) -> list[dict]:
    """Parse LLM JSON response into list of normalised objection dicts."""
    match = re.search(r'\[.*?\]', response_text, re.DOTALL)
    if not match:
        return []
    try:
        items = json.loads(match.group())
        result = []
        for item in items:
            if not isinstance(item, dict):
                continue
            cat = str(item.get("category", "OTHER")).upper()
            if cat not in CATEGORIES:
                cat = "OTHER"
            result.append({
                "category": cat,
                "objection_text": str(item.get("objection_text", ""))[:500],
                "representative_quote": str(item.get("representative_quote", ""))[:500],
                "count": max(1, int(item.get("count", 1))),
            })
        return result
    except (json.JSONDecodeError, ValueError, TypeError):
        return []


def fallback_extract(quotes: list[str]) -> list[dict]:
    """Keyword-based extraction used when LLM is unavailable."""
    from collections import defaultdict
    buckets: dict[str, list[str]] = defaultdict(list)
    for q in quotes:
        cat = classify_objection_keyword(q)
        buckets[cat].append(q)
    return [
        {
            "category": cat,
            "objection_text": f"{cat.title()} objection ({len(qs)} quotes)",
            "representative_quote": qs[0] if qs else "",
            "count": len(qs),
        }
        for cat, qs in buckets.items()
    ]
