"""
External Signals + Anomaly router (vision §11.3; arch §15.1).
POST /signals/anomaly
"""
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel
from typing import Optional
import os

from app.services.signals_service import (
    classify_anomaly,
    compute_cpm_z_score,
    route_signal_for_funnel,
    extract_narrative_context,
    summarise_weather_signals,
)
from app.security import check_internal_key
from app.services import news_sentiment_service as news

router = APIRouter()



def _check_key(key: Optional[str]) -> None:
    check_internal_key(key)


class AnomalyRequest(BaseModel):
    anchor_verdict: str = "NONE"        # from /anchors/divergence
    site_health_open: bool = False
    fraud_share_pct: float = 0.0
    news_label: str = "NEUTRAL"         # roadmap 3-12
    offer_problem: bool = False
    current_cpm: Optional[float] = None
    cpm_history: list[float] = []
    calendar_tags: list[str] = []
    fraud_threshold_pct: float = 15.0
    cpm_z_threshold: float = 2.5


@router.post("/anomaly")
def classify(
    req: AnomalyRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)

    cpm_z = None
    if req.current_cpm is not None and req.cpm_history:
        cpm_z = compute_cpm_z_score(req.current_cpm, req.cpm_history)

    return classify_anomaly(
        anchor_verdict=req.anchor_verdict,
        site_health_open=req.site_health_open,
        fraud_share_pct=req.fraud_share_pct,
        offer_problem=req.offer_problem,
        cpm_z_score=cpm_z,
        calendar_tags=req.calendar_tags,
        fraud_threshold_pct=req.fraud_threshold_pct,
        cpm_z_threshold=req.cpm_z_threshold,
        news_label=req.news_label,
    )


# ─── Funnel Signal Routing (8-10) ─────────────────────────────────────────────

class CalendarEvent(BaseModel):
    event_type:  str = ""
    event_name:  str = ""
    days_until:  int = 99


class RouteSignalRequest(BaseModel):
    funnel_key:      str
    weather_role:    str               # 'NARRATIVE' | 'DEMAND'
    raw_signals:     list[dict] = []   # rows from external_signals
    news_tags:       list[str]  = []
    calendar_events: list[CalendarEvent] = []


class NarrativeContextRequest(BaseModel):
    raw_signals:     list[dict] = []
    news_tags:       list[str]  = []
    calendar_events: list[CalendarEvent] = []


@router.post("/route")
def route_signal(
    req: RouteSignalRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    weather = summarise_weather_signals(req.raw_signals)
    events  = [e.model_dump() for e in req.calendar_events]
    return route_signal_for_funnel(
        funnel_key=req.funnel_key,
        weather_role=req.weather_role,
        weather_summary=weather,
        news_tags=req.news_tags,
        calendar_events=events,
    )


@router.post("/narrative-context")
def narrative_context(
    req: NarrativeContextRequest,
    x_internal_api_key: Optional[str] = Header(None),
):
    _check_key(x_internal_api_key)
    weather = summarise_weather_signals(req.raw_signals)
    events  = [e.model_dump() for e in req.calendar_events]
    return extract_narrative_context(
        weather_summary=weather,
        news_tags=req.news_tags,
        calendar_events=events,
    )


# ─── News headline sentiment (roadmap 3-12) ───────────────────────────────────

class NewsSentimentRequest(BaseModel):
    tenant_id: str
    feeds: list[str]                   # RSS / Atom URLs (tenants.settings.news_feeds)
    keywords: list[str] = []           # relevance filter (tenants.settings.news_keywords)
    hours: int = 24
    use_llm: bool = True               # Claude scoring when ANTHROPIC_API_KEY is set, else lexicon
    max_items: int = 40


async def _llm_scores(items: list[dict]) -> list[float] | None:
    """Per-headline sentiment in [-1, 1] from Claude; None when the key is missing or the call fails."""
    from app.config import settings
    if not settings.anthropic_api_key or not items:
        return None
    try:
        import json
        import anthropic
        client = anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key)
        lines = "\n".join(f"{i}. {it.get('title', '')}" for i, it in enumerate(items))
        msg = await client.messages.create(
            model="claude-haiku-5-5", max_tokens=600,
            messages=[{"role": "user", "content":
                "Score each headline's sentiment for an investor audience from -1 (very negative) to 1 "
                "(very positive). Reply with a JSON array of numbers only, one per line, same order.\n\n" + lines}],
        )
        text = "".join(getattr(b, "text", "") for b in msg.content)
        scores = json.loads(text[text.index("["): text.rindex("]") + 1])
        if len(scores) != len(items):
            return None
        return [max(-1.0, min(1.0, float(x))) for x in scores]
    except Exception:
        return None


@router.post("/news-sentiment")
async def news_sentiment(req: NewsSentimentRequest):
    import httpx
    from app.security import assert_public_http_url
    items: list[dict] = []
    errors: list[str] = []
    async with httpx.AsyncClient(timeout=20, follow_redirects=True) as client:
        for url in req.feeds[:10]:
            try:
                assert_public_http_url(url)
                resp = await client.get(url, headers={"User-Agent": "MarketingAI-NewsSignal/1.0"})
                resp.raise_for_status()
                items.extend(news.parse_rss(resp.text))
            except Exception as exc:
                errors.append(f"{url}: {str(exc)[:120]}")
    relevant = news.filter_items(items, req.keywords, hours=req.hours)[: req.max_items]
    scores = await _llm_scores(relevant) if req.use_llm else None
    agg = news.aggregate(relevant, scores)
    agg.update({
        "tenant_id": req.tenant_id,
        "label": news.signal_label(agg["sentiment_mean"], agg["negative_share"]),
        "scoring": "llm" if scores is not None else "lexicon",
        "fetched_items": len(items),
        "errors": errors,
    })
    return agg
