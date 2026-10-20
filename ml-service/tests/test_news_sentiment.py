"""Roadmap 3-12: news headline sentiment — RSS parsing, relevance filter, lexicon scoring, aggregation."""
from datetime import datetime, timedelta, timezone

from app.services.news_sentiment_service import (
    aggregate,
    filter_items,
    lexicon_sentiment,
    parse_rss,
    signal_label,
    theme_tags,
)

RSS = """<?xml version="1.0"?><rss version="2.0"><channel><title>Feed</title>
<item><title>SEC charges fund manager with fraud</title><link>https://n.example/1</link>
<pubDate>Mon, 19 Oct 2026 12:00:00 GMT</pubDate><description>Regulators crack down.</description></item>
<item><title>Agtech startup raises Series A as irrigation demand surges</title><link>https://n.example/2</link>
<pubDate>Mon, 19 Oct 2026 13:00:00 GMT</pubDate></item>
<item><title>Weather: mild week ahead</title><pubDate>Mon, 01 Jan 2024 00:00:00 GMT</pubDate></item>
</channel></rss>"""

ATOM = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Markets rally to record high</title>
<link href="https://n.example/a"/><updated>2026-10-19T09:00:00Z</updated></entry></feed>"""


def test_parse_rss_and_atom():
    items = parse_rss(RSS)
    assert [i["title"] for i in items][:2] == ["SEC charges fund manager with fraud",
                                               "Agtech startup raises Series A as irrigation demand surges"]
    assert items[0]["published"].tzinfo is not None and items[0]["link"].endswith("/1")
    atom = parse_rss(ATOM)
    assert atom[0]["title"] == "Markets rally to record high" and atom[0]["link"].endswith("/a")
    assert parse_rss("") == [] and parse_rss("<not xml") == []


def test_filter_by_recency_and_keywords():
    now = datetime(2026, 10, 20, 0, 0, tzinfo=timezone.utc)
    items = parse_rss(RSS)
    recent = filter_items(items, [], hours=24, now=now)
    assert len(recent) == 2                                   # the 2024 item is dropped
    agri = filter_items(items, ["irrigation", "farm"], hours=48, now=now)
    assert [i["title"] for i in agri] == ["Agtech startup raises Series A as irrigation demand surges"]


def test_lexicon_sentiment_signs():
    assert lexicon_sentiment("SEC charges fund manager with fraud") < -0.5
    assert lexicon_sentiment("Markets rally to record high") > 0.5
    assert lexicon_sentiment("Weather: mild week ahead") == 0.0


def test_theme_tags():
    assert "fraud" in theme_tags("SEC charges fund manager with fraud")
    assert "regulation" in theme_tags("SEC charges fund manager with fraud")
    tags = theme_tags("irrigation demand surges on farms")
    assert "agri" in tags and "market_up" in tags          # "surges" is also a market-up term
    assert theme_tags("quiet week in the valley") == []


def test_aggregate_and_label():
    items = filter_items(parse_rss(RSS), [], hours=24, now=datetime(2026, 10, 20, tzinfo=timezone.utc))
    agg = aggregate(items)
    assert agg["n_items"] == 2 and -1 <= agg["sentiment_mean"] <= 1
    assert agg["top_items"][0]["title"].startswith("SEC charges")      # most negative first
    assert "fraud" in agg["tags"]
    assert signal_label(-0.6, 0.5) == "NEGATIVE"
    assert signal_label(0.4, 0.0) == "POSITIVE"
    assert signal_label(0.0, 0.1) == "NEUTRAL" and signal_label(None, None) == "NEUTRAL"


def test_aggregate_with_external_scores():
    items = [{"title": "a"}, {"title": "b"}]
    agg = aggregate(items, scores=[-0.9, 0.1])
    assert agg["sentiment_mean"] == -0.4 and agg["negative_share"] == 0.5


def test_aggregate_empty():
    assert aggregate([])["n_items"] == 0
