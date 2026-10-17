"""
Tests for 8-10: route_signal_for_funnel + extract_narrative_context
(vision §19.13; arch §16.10)
"""
import pytest
from app.services.signals_service import (
    route_signal_for_funnel,
    extract_narrative_context,
    summarise_weather_signals,
)


# ─── fixtures ────────────────────────────────────────────────────────────────

DROUGHT_SEVERE_SIGNALS = [
    {"value_tags": ["drought_severe", "heat_stress"], "value_num": None},
]

DROUGHT_MODERATE_SIGNALS = [
    {"value_tags": ["drought_moderate"], "value_num": None},
]

NORMAL_SIGNALS = [
    {"value_tags": ["season_start"], "value_num": 45.0},
]

CAL_ROUND_CLOSE_SOON = [
    {"event_type": "round_close", "event_name": "InteliGel Round 1 Close", "days_until": 14},
]

CAL_ROUND_CLOSE_FAR = [
    {"event_type": "round_close", "event_name": "InteliGel Round 1 Close", "days_until": 60},
]

CAL_REGULATORY_SOON = [
    {"event_type": "regulatory", "event_name": "Illinois PTI deadline", "days_until": 5},
    {"event_type": "webinar",    "event_name": "InteliGel Grower Webinar", "days_until": 3},
]


# ─── summarise_weather_signals ───────────────────────────────────────────────

def test_summarise_drought_severe():
    w = summarise_weather_signals(DROUGHT_SEVERE_SIGNALS)
    assert w["is_drought"] is True
    assert "drought_severe" in w["weather_bucket"]
    assert w["heat_stress"] is True


def test_summarise_normal():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    assert w["is_drought"] is False
    assert w["heat_stress"] is False


# ─── route_signal_for_funnel — DEMAND ────────────────────────────────────────

def test_demand_drought_severe_multiplier():
    w = summarise_weather_signals(DROUGHT_SEVERE_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="grower",
        weather_role="DEMAND",
        weather_summary=w,
        news_tags=[],
        calendar_events=[],
    )
    assert result["budget_multiplier"] == 1.6
    assert result["weather_role"] == "DEMAND"


def test_demand_drought_moderate_multiplier():
    w = summarise_weather_signals(DROUGHT_MODERATE_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="grower",
        weather_role="DEMAND",
        weather_summary=w,
        news_tags=[],
        calendar_events=[],
    )
    assert result["budget_multiplier"] == 1.3


def test_demand_normal_multiplier():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="grower",
        weather_role="DEMAND",
        weather_summary=w,
        news_tags=[],
        calendar_events=[],
    )
    assert result["budget_multiplier"] == 1.0


def test_demand_calendar_urgency_bump():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="grower",
        weather_role="DEMAND",
        weather_summary=w,
        news_tags=[],
        calendar_events=CAL_REGULATORY_SOON,
    )
    # 2 urgent events → 1.0 * 1.2 = 1.2
    assert result["budget_multiplier"] == pytest.approx(1.2, abs=0.01)
    assert len(result["urgent_events"]) == 2


def test_demand_drought_plus_urgency():
    w = summarise_weather_signals(DROUGHT_MODERATE_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="grower",
        weather_role="DEMAND",
        weather_summary=w,
        news_tags=[],
        calendar_events=CAL_REGULATORY_SOON,
    )
    # 1.3 * 1.2 = 1.56
    assert result["budget_multiplier"] == pytest.approx(1.56, abs=0.01)


# ─── route_signal_for_funnel — NARRATIVE ─────────────────────────────────────

def test_narrative_never_changes_budget():
    w = summarise_weather_signals(DROUGHT_SEVERE_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="investor",
        weather_role="NARRATIVE",
        weather_summary=w,
        news_tags=["agtech_funding", "rate_change"],
        calendar_events=[],
    )
    assert result["budget_multiplier"] == 1.0
    assert "water_scarcity_context" in result["urgency_tags"]
    assert "agtech_funding" in result["urgency_tags"]
    assert "rate_change" in result["urgency_tags"]


def test_narrative_round_close_urgency():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="investor",
        weather_role="NARRATIVE",
        weather_summary=w,
        news_tags=[],
        calendar_events=CAL_ROUND_CLOSE_SOON,
    )
    assert "round_close_approaching" in result["urgency_tags"]


def test_narrative_round_close_far_not_urgent():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    result = route_signal_for_funnel(
        funnel_key="investor",
        weather_role="NARRATIVE",
        weather_summary=w,
        news_tags=[],
        calendar_events=CAL_ROUND_CLOSE_FAR,
    )
    assert "round_close_approaching" not in result["urgency_tags"]


# ─── extract_narrative_context ───────────────────────────────────────────────

def test_narrative_context_drought():
    w = summarise_weather_signals(DROUGHT_SEVERE_SIGNALS)
    ctx = extract_narrative_context(
        weather_summary=w,
        news_tags=["agtech_funding"],
        calendar_events=[],
    )
    lines = ctx["context_lines"]
    assert any("drought" in l.lower() for l in lines)
    assert any("AgTech" in l for l in lines)
    assert ctx["has_round_close_urgency"] is False


def test_narrative_context_round_close():
    w = summarise_weather_signals(NORMAL_SIGNALS)
    ctx = extract_narrative_context(
        weather_summary=w,
        news_tags=[],
        calendar_events=CAL_ROUND_CLOSE_SOON,
    )
    assert ctx["has_round_close_urgency"] is True
    assert any("round" in l.lower() for l in ctx["context_lines"])


def test_narrative_context_no_meta_personal_attr():
    """Context lines must describe the offer/market — never the viewer."""
    w = summarise_weather_signals(DROUGHT_SEVERE_SIGNALS)
    ctx = extract_narrative_context(
        weather_summary=w,
        news_tags=["sec_reg", "rate_change"],
        calendar_events=CAL_ROUND_CLOSE_SOON,
    )
    forbidden_phrases = [
        "are you", "you are", "your income", "your net worth",
        "accredited investor?", "do you qualify",
    ]
    for line in ctx["context_lines"]:
        for phrase in forbidden_phrases:
            assert phrase.lower() not in line.lower(), \
                f"META_PERSONAL_ATTR violation in context line: {line!r}"


def test_narrative_context_empty_signals():
    w = summarise_weather_signals([])
    ctx = extract_narrative_context(
        weather_summary=w, news_tags=[], calendar_events=[],
    )
    assert ctx["context_lines"] == []
    assert ctx["has_round_close_urgency"] is False
