"""CVR-collapse detector (roadmap 9-8; arch §9 / §15.3) — pure logic, no FastAPI imports.

Rule (vision §11.4 "Site Health Guard"): traffic is stable but conversions vanished within the last
hour → something broke between the ad and the thank-you page (form, tracking, redirect), not the market.

    detect(series, min_sessions=100, traffic_tolerance=0.2, collapse_ratio=0.1)

series: 24 hourly rows {hour, sessions, conversions} (optionally impressions, clicks); the LAST row is
the current hour. Traffic metric = CTR (clicks / impressions) when both are present, else sessions.
Verdict:
  COLLAPSE           traffic_last within ±tolerance of the 24 h mean (excluding the last hour)
                     AND cvr_last < collapse_ratio × cvr_mean_24h AND sessions_last ≥ min_sessions
                     AND cvr_mean_24h > 0
  OK                 otherwise with enough data
  INSUFFICIENT_DATA  < 6 rows, or no conversions in the baseline, or sessions_last < min_sessions
"""
from __future__ import annotations

from typing import Any


def _f(row: dict, key: str) -> float:
    try:
        return float(row.get(key) or 0.0)
    except (TypeError, ValueError):
        return 0.0


def _traffic(row: dict) -> float:
    imp, clicks = _f(row, "impressions"), _f(row, "clicks")
    if imp > 0 and "clicks" in row:
        return clicks / imp
    return _f(row, "sessions")


def detect(series: list[dict], min_sessions: int = 100, traffic_tolerance: float = 0.2,
           collapse_ratio: float = 0.1) -> dict[str, Any]:
    rows = [r for r in series if isinstance(r, dict)]
    if len(rows) < 6:
        return {"verdict": "INSUFFICIENT_DATA", "reason": "need >= 6 hourly rows", "n_rows": len(rows)}
    last, base = rows[-1], rows[:-1]

    sessions_last = _f(last, "sessions")
    conv_last = _f(last, "conversions")
    base_sessions = sum(_f(r, "sessions") for r in base)
    base_conv = sum(_f(r, "conversions") for r in base)
    cvr_mean = base_conv / base_sessions if base_sessions > 0 else 0.0
    cvr_last = conv_last / sessions_last if sessions_last > 0 else 0.0

    traffic_vals = [_traffic(r) for r in base]
    traffic_mean = sum(traffic_vals) / len(traffic_vals) if traffic_vals else 0.0
    traffic_last = _traffic(last)
    traffic_stable = traffic_mean > 0 and abs(traffic_last - traffic_mean) <= traffic_tolerance * traffic_mean

    out = {
        "sessions_last": sessions_last, "cvr_last": round(cvr_last, 5), "cvr_mean_24h": round(cvr_mean, 5),
        "traffic_last": round(traffic_last, 5), "traffic_mean_24h": round(traffic_mean, 5),
        "traffic_stable": traffic_stable, "n_rows": len(rows),
    }
    if sessions_last < min_sessions:
        return {**out, "verdict": "INSUFFICIENT_DATA", "reason": f"sessions_last {int(sessions_last)} < {min_sessions}"}
    if cvr_mean <= 0:
        return {**out, "verdict": "INSUFFICIENT_DATA", "reason": "no conversions in the 24 h baseline"}
    if traffic_stable and cvr_last < collapse_ratio * cvr_mean:
        return {**out, "verdict": "COLLAPSE",
                "reason": f"CVR {cvr_last:.4f} < {collapse_ratio:.0%} of 24h mean {cvr_mean:.4f} on stable traffic"}
    if not traffic_stable:
        return {**out, "verdict": "OK", "reason": "traffic changed too — not a conversion-path failure"}
    return {**out, "verdict": "OK", "reason": "conversion rate within range"}


def detect_many(entities: list[dict], **kwargs) -> list[dict]:
    """[{id, series}] → [{id, verdict, …}] in input order."""
    results = []
    for e in entities:
        res = detect(e.get("series") or [], **kwargs)
        res["id"] = e.get("id")
        results.append(res)
    return results
