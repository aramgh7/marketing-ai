"""News headline sentiment (roadmap 3-12; vision §11.3 "news" external signal) — pure logic.

RSS parsing, keyword relevance filter, lexicon sentiment (fallback when no LLM key), theme tags
and aggregation into one external_signals row per tenant per day:
    value_num = mean sentiment in [-1, 1], value_tags = themes seen (regulation, rates, …).
The LLM path (Claude) lives in the router; it receives the same items and returns per-item
scores, which `aggregate()` consumes identically.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

NEGATIVE = {
    "crash": -0.8, "plunge": -0.8, "collapse": -0.9, "fraud": -0.9, "scandal": -0.8, "lawsuit": -0.6,
    "recession": -0.7, "layoffs": -0.6, "bankrupt": -0.9, "ban": -0.6, "crackdown": -0.7, "probe": -0.5,
    "sec charges": -0.9, "drought": -0.5, "shortage": -0.5, "falls": -0.4, "drop": -0.4, "slump": -0.6,
    "default": -0.7, "warning": -0.4, "fine": -0.4, "halted": -0.6, "loss": -0.4, "cuts": -0.3,
}
POSITIVE = {
    "surge": 0.6, "record high": 0.7, "rally": 0.6, "boom": 0.6, "approval": 0.6, "approved": 0.6,
    "breakthrough": 0.7, "funding": 0.5, "raises": 0.5, "growth": 0.4, "expands": 0.4, "profit": 0.5,
    "wins": 0.4, "gains": 0.4, "recovery": 0.5, "rebound": 0.5, "launch": 0.3, "partnership": 0.4,
}
THEMES: dict[str, list[str]] = {
    "regulation":  ["sec", "regulat", "compliance", "ban", "crackdown", "law", "rule"],
    "rates":       ["interest rate", "fed", "rate hike", "rate cut", "inflation", "treasury", "yield"],
    "market_drop": ["crash", "plunge", "sell-off", "selloff", "bear market", "correction", "slump"],
    "market_up":   ["rally", "record high", "bull", "surge", "rebound"],
    "fraud":       ["fraud", "scam", "ponzi", "charges", "indict"],
    "agri":        ["drought", "harvest", "crop", "farm", "irrigation", "yield per acre", "usda"],
    "funding":     ["funding", "raises", "series a", "series b", "valuation", "ipo", "reg d", "private placement"],
    "weather":     ["heat", "storm", "flood", "wildfire", "hurricane"],
}


def parse_rss(xml_text: str) -> list[dict]:
    """RSS 2.0 / Atom items → [{title, link, published (aware datetime or None), summary}]."""
    if not xml_text or not xml_text.strip():
        return []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    items: list[dict] = []
    ns = {"atom": "http://www.w3.org/2005/Atom"}
    for it in root.iter("item"):
        items.append({
            "title": (it.findtext("title") or "").strip(),
            "link": (it.findtext("link") or "").strip(),
            "summary": (it.findtext("description") or "").strip()[:500],
            "published": _parse_date(it.findtext("pubDate")),
        })
    for e in root.findall(".//atom:entry", ns):
        link = e.find("atom:link", ns)
        items.append({
            "title": (e.findtext("atom:title", default="", namespaces=ns) or "").strip(),
            "link": (link.get("href") if link is not None else "") or "",
            "summary": (e.findtext("atom:summary", default="", namespaces=ns) or "").strip()[:500],
            "published": _parse_date(e.findtext("atom:updated", default=None, namespaces=ns)
                                     or e.findtext("atom:published", default=None, namespaces=ns)),
        })
    return [i for i in items if i["title"]]


def _parse_date(raw: str | None):
    if not raw:
        return None
    try:
        dt = parsedate_to_datetime(raw)
    except (TypeError, ValueError):
        try:
            dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def filter_items(items: list[dict], keywords: list[str], hours: int = 24, now: datetime | None = None) -> list[dict]:
    """Keep recent items whose title/summary mention any keyword (no keywords = keep all recent)."""
    now = now or datetime.now(timezone.utc)
    cutoff = now - timedelta(hours=hours)
    kws = [k.lower() for k in keywords if k and k.strip()]
    out = []
    for it in items:
        pub = it.get("published")
        if pub is not None and pub < cutoff:
            continue
        text = f"{it.get('title', '')} {it.get('summary', '')}".lower()
        if kws and not any(k in text for k in kws):
            continue
        out.append(it)
    return out


def lexicon_sentiment(text: str) -> float:
    """Mean of matched lexicon weights, clipped to [-1, 1]; 0 when nothing matches."""
    low = (text or "").lower()
    scores = [w for term, w in {**NEGATIVE, **POSITIVE}.items() if term in low]
    if not scores:
        return 0.0
    return max(-1.0, min(1.0, sum(scores) / len(scores)))


def theme_tags(text: str) -> list[str]:
    low = (text or "").lower()
    return [theme for theme, terms in THEMES.items() if any(t in low for t in terms)]


def aggregate(items: list[dict], scores: list[float] | None = None) -> dict:
    """One signal from scored items: mean, negative share, theme tags, top negative headlines."""
    if not items:
        return {"n_items": 0, "sentiment_mean": None, "negative_share": None, "tags": [], "top_items": []}
    if scores is None:
        scores = [lexicon_sentiment(f"{i.get('title', '')} {i.get('summary', '')}") for i in items]
    n = len(items)
    mean = round(sum(scores) / n, 4)
    negative_share = round(sum(1 for s in scores if s <= -0.3) / n, 4)
    tags: dict[str, int] = {}
    for it in items:
        for t in theme_tags(f"{it.get('title', '')} {it.get('summary', '')}"):
            tags[t] = tags.get(t, 0) + 1
    ranked = sorted(zip(scores, items), key=lambda p: p[0])
    top = [{"title": it["title"], "link": it.get("link", ""), "score": round(s, 3)} for s, it in ranked[:5]]
    return {"n_items": n, "sentiment_mean": mean, "negative_share": negative_share,
            "tags": sorted(tags, key=lambda t: (-tags[t], t)), "tag_counts": tags, "top_items": top}


def signal_label(sentiment_mean: float | None, negative_share: float | None) -> str:
    """NEGATIVE / NEUTRAL / POSITIVE for the anomaly chain (MARKET evidence when NEGATIVE)."""
    if sentiment_mean is None:
        return "NEUTRAL"
    if sentiment_mean <= -0.25 or (negative_share or 0) >= 0.5:
        return "NEGATIVE"
    if sentiment_mean >= 0.25:
        return "POSITIVE"
    return "NEUTRAL"
