"""
External Signals + Baseline Anomaly Service — no FastAPI/Pydantic imports.
Vision §11.3, §8.2; arch §15.1.

Anomaly labels: MARKET / CREATIVE / TECHNICAL / FRAUD / NONE
"""
from __future__ import annotations

import math
from typing import Optional


# ─── Anomaly Attribution ──────────────────────────────────────────────────────

def classify_anomaly(
    anchor_verdict: str,                # MARKET / CREATIVE / NONE from anchors_service
    site_health_open: bool,             # True if a site_health_incident is open
    fraud_share_pct: float,             # fraction of suspicious sessions [0, 100]
    offer_problem: bool,                # True if mmf_diagnoses shows OFFER_PROBLEM
    cpm_z_score: Optional[float],       # z-score of current CPM vs 14-day rolling
    calendar_tags: list[str],           # e.g. ["black_friday", "drought_moderate"]
    fraud_threshold_pct: float = 15.0,
    cpm_z_threshold: float = 2.5,
) -> dict:
    """
    Returns {verdict, evidence} per §8.2 three-row table.

    Priority: TECHNICAL > FRAUD > MARKET > CREATIVE > NONE
    """
    evidence = []

    if site_health_open:
        evidence.append("site_health_incident_open")
        return {"verdict": "TECHNICAL", "evidence": evidence}

    if fraud_share_pct >= fraud_threshold_pct:
        evidence.append(f"fraud_share={fraud_share_pct:.1f}%")
        return {"verdict": "FRAUD", "evidence": evidence}

    if anchor_verdict == "MARKET":
        evidence.append("anchors_dropped_vs_baseline")
        if cpm_z_score is not None and cpm_z_score > cpm_z_threshold:
            evidence.append(f"cpm_z={cpm_z_score:.2f}")
        if calendar_tags:
            evidence.append(f"calendar={calendar_tags}")
        return {"verdict": "MARKET", "evidence": evidence}

    if anchor_verdict == "CREATIVE":
        evidence.append("tests_dropped_anchors_stable")
        return {"verdict": "CREATIVE", "evidence": evidence}

    return {"verdict": "NONE", "evidence": []}


def compute_cpm_z_score(current_cpm: float, history: list[float]) -> Optional[float]:
    """Z-score of current_cpm vs 14-day rolling history."""
    if len(history) < 5:
        return None
    mean = sum(history) / len(history)
    var = sum((x - mean) ** 2 for x in history) / len(history)
    std = math.sqrt(var)
    if std < 1e-9:
        return 0.0
    return (current_cpm - mean) / std


def summarise_weather_signals(signals: list[dict]) -> dict:
    """
    Extract weather bucket and drought flag from external_signals rows.
    Returns {weather_bucket, is_drought, growing_degree_days, heat_stress}.
    """
    weather = {"weather_bucket": "normal", "is_drought": False,
               "growing_degree_days": None, "heat_stress": False}
    for s in signals:
        tags = s.get("value_tags", []) or []
        if "drought_severe" in tags or "drought_extreme" in tags:
            weather["is_drought"] = True
            weather["weather_bucket"] = "drought_severe"
        elif "drought_moderate" in tags and weather["weather_bucket"] == "normal":
            weather["is_drought"] = True
            weather["weather_bucket"] = "drought_moderate"
        if "heat_stress" in tags:
            weather["heat_stress"] = True
        if "season_start" in tags:
            weather["weather_bucket"] = weather.get("weather_bucket", "normal") + "_season_start"
        val = s.get("value_num")
        if val is not None and "growing_degree_days" in tags:
            weather["growing_degree_days"] = val
    return weather


# ─── Funnel-Aware Signal Routing (vision §19.13; arch §16.10) ────────────────

def route_signal_for_funnel(
    funnel_key: str,
    weather_role: str,          # 'NARRATIVE' | 'DEMAND'  from funnel_signal_config
    weather_summary: dict,      # output of summarise_weather_signals()
    news_tags: list[str],       # matched from tenant news_tags list
    calendar_events: list[dict],  # upcoming {event_type, event_name, days_until}
) -> dict:
    """
    Routes external signals to the correct funnel use-case.

    DEMAND  funnels (grower webinar / field events): weather_bucket drives
            a budget_multiplier: drought_severe → 1.6, drought_moderate → 1.3,
            heat_stress → 1.2, normal → 1.0.  Calendar urgency bump if
            event ≤7 days out.

    NARRATIVE funnels (investor): weather and news are context for the brief
            only — budget is unaffected.  Returns urgency_tags list for the
            brief generator.
    """
    result: dict = {"funnel_key": funnel_key, "weather_role": weather_role}
    bucket = weather_summary.get("weather_bucket", "normal")

    if weather_role == "DEMAND":
        multiplier = 1.0
        if "drought_severe" in bucket:
            multiplier = 1.6
        elif "drought_moderate" in bucket:
            multiplier = 1.3
        elif weather_summary.get("heat_stress"):
            multiplier = 1.2

        # Calendar urgency: nearest upcoming event ≤7 days adds 10 % per event
        urgent = [e for e in calendar_events if e.get("days_until", 99) <= 7]
        if urgent:
            multiplier = round(multiplier * (1.0 + 0.1 * len(urgent)), 3)

        result["budget_multiplier"] = multiplier
        result["urgent_events"]     = [e.get("event_name") for e in urgent]

    else:  # NARRATIVE
        urgency_tags: list[str] = []
        if weather_summary.get("is_drought"):
            urgency_tags.append("water_scarcity_context")
        for tag in news_tags:
            if tag in ("rate_change", "sec_reg", "agtech_funding"):
                urgency_tags.append(tag)
        close_events = [e for e in calendar_events
                        if e.get("event_type") == "round_close"
                        and e.get("days_until", 99) <= 30]
        if close_events:
            urgency_tags.append("round_close_approaching")

        result["urgency_tags"]    = urgency_tags
        result["budget_multiplier"] = 1.0   # NARRATIVE never touches budget

    return result


def extract_narrative_context(
    weather_summary: dict,
    news_tags: list[str],
    calendar_events: list[dict],
) -> dict:
    """
    Produces a brief context block for NARRATIVE (investor) funnels.
    Returns a dict the brief generator can inject into 'market_context'.

    Context lines are factual, offer-centric: they describe external conditions
    relevant to the investment opportunity, never describe the viewer.
    (Complies with META_PERSONAL_ATTR rule.)
    """
    lines: list[str] = []

    bucket = weather_summary.get("weather_bucket", "normal")
    if "drought_severe" in bucket:
        lines.append("Severe drought conditions are increasing AgTech investment urgency.")
    elif "drought_moderate" in bucket:
        lines.append("Moderate drought conditions are driving interest in water-efficient solutions.")

    for tag in news_tags:
        if tag == "agtech_funding":
            lines.append("AgTech funding activity has been elevated in recent weeks.")
        elif tag == "water_policy":
            lines.append("Recent water-policy developments are shaping the regulatory landscape.")
        elif tag == "rate_change":
            lines.append("Recent rate movements have shifted the risk-return calculus for private placements.")
        elif tag == "sec_reg":
            lines.append("Regulatory activity in the securities space warrants monitoring.")

    close_events = sorted(
        [e for e in calendar_events
         if e.get("event_type") == "round_close" and e.get("days_until", 99) <= 30],
        key=lambda e: e.get("days_until", 99),
    )
    if close_events:
        nearest = close_events[0]
        days = nearest.get("days_until", "?")
        lines.append(f"The offering's round closes in approximately {days} days.")

    return {
        "context_lines": lines,
        "weather_bucket": bucket,
        "news_tags_matched": news_tags,
        "has_round_close_urgency": bool(close_events),
    }
