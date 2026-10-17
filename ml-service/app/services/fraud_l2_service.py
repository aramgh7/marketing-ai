"""
Fraud Layer 2 rules — pure logic, no FastAPI/Pydantic imports.
Rules: AUTOMATED_TIMING, LINEAR_SCROLL, IP+UA+VIEWPORT cluster (CGNAT-safe), ZERO_SCROLL.
"""
from __future__ import annotations
import math
from typing import Optional


# ── AUTOMATED_TIMING ──────────────────────────────────────────────────────────

def check_automated_timing(
    inter_event_gaps_ms: list[float],
    sigma_threshold_ms: float = 50.0,
    min_events: int = 5,
) -> dict:
    """
    AUTOMATED_TIMING: std-dev of inter-event gaps < 50ms over >= 5 events.
    Human timing is irregular; bots have near-constant intervals.
    """
    if len(inter_event_gaps_ms) < min_events:
        return {
            "rule": "AUTOMATED_TIMING",
            "fired": False,
            "reason": f"Insufficient events ({len(inter_event_gaps_ms)} < {min_events})",
        }
    n = len(inter_event_gaps_ms)
    mean = sum(inter_event_gaps_ms) / n
    variance = sum((x - mean) ** 2 for x in inter_event_gaps_ms) / (n - 1) if n > 1 else 0.0
    sigma = math.sqrt(variance)
    fired = sigma < sigma_threshold_ms
    return {
        "rule": "AUTOMATED_TIMING",
        "fired": fired,
        "sigma_ms": round(sigma, 2),
        "mean_gap_ms": round(mean, 2),
        "reason": f"σ={sigma:.1f}ms {'<' if fired else '>='} {sigma_threshold_ms}ms threshold",
    }


# ── LINEAR_SCROLL ──────────────────────────────────────────────────────────────

def check_linear_scroll(
    scroll_events: list[dict],
    r2_threshold: float = 0.98,
    min_points: int = 4,
    is_touch: bool = False,
) -> dict:
    """
    LINEAR_SCROLL: constant-velocity scroll (no acceleration) → bot.
    R² ≥ 0.98 of depth vs time = near-perfect linear fit → suspicious.
    Split: touch (mobile) vs mouse (desktop).
    """
    if len(scroll_events) < min_points:
        return {
            "rule": "LINEAR_SCROLL",
            "fired": False,
            "reason": f"Insufficient scroll points ({len(scroll_events)} < {min_points})",
        }
    t0 = scroll_events[0]["ts_ms"]
    xs = [float(e["ts_ms"] - t0) for e in scroll_events]
    ys = [float(e["depth_pct"]) for e in scroll_events]
    n = len(xs)
    sum_x  = sum(xs)
    sum_y  = sum(ys)
    sum_xy = sum(x * y for x, y in zip(xs, ys))
    sum_x2 = sum(x * x for x in xs)
    denom  = n * sum_x2 - sum_x ** 2
    if denom == 0:
        return {
            "rule": "LINEAR_SCROLL",
            "fired": True,
            "r2": 1.0,
            "slope": 0.0,
            "device_type": "touch" if is_touch else "mouse",
            "reason": "All scroll events at same timestamp — zero time variance",
        }
    slope     = (n * sum_xy - sum_x * sum_y) / denom
    intercept = (sum_y - slope * sum_x) / n
    predicted = [slope * x + intercept for x in xs]
    ss_res = sum((y - yhat) ** 2 for y, yhat in zip(ys, predicted))
    ss_tot = sum((y - sum_y / n) ** 2 for y in ys)
    r2    = 1.0 - (ss_res / ss_tot) if ss_tot > 0 else 1.0
    fired = r2 >= r2_threshold
    return {
        "rule": "LINEAR_SCROLL",
        "fired": fired,
        "r2": round(r2, 4),
        "slope": round(slope, 4),
        "device_type": "touch" if is_touch else "mouse",
        "reason": f"R²={r2:.3f} {'≥' if fired else '<'} {r2_threshold} — {'bot-like linear' if fired else 'human-like'} scroll",
    }


# ── IP + UA + VIEWPORT CLUSTER (CGNAT-safe) ───────────────────────────────────

def check_ip_ua_viewport_cluster(
    ip_address: str,
    user_agent: str,
    viewport_w: int,
    viewport_h: int,
    recent_sessions_same_fingerprint: int,
    cluster_threshold: int = 50,
) -> dict:
    """
    IP + UA + viewport fingerprint cluster.
    CGNAT users share IP but have diverse UA+viewport → no cluster.
    Bots share all three → cluster.
    """
    fingerprint = f"{ip_address}|{user_agent[:50]}|{viewport_w}x{viewport_h}"
    fired = recent_sessions_same_fingerprint >= cluster_threshold
    return {
        "rule": "IP_UA_VIEWPORT_CLUSTER",
        "fired": fired,
        "cluster_size": recent_sessions_same_fingerprint,
        "fingerprint_prefix": fingerprint[:40] + "…",
        "reason": f"Cluster size {recent_sessions_same_fingerprint} {'≥' if fired else '<'} {cluster_threshold}",
    }


# ── ZERO_SCROLL (touch vs mouse) ──────────────────────────────────────────────

def check_zero_scroll(
    scroll_depth_pct: float,
    has_mouse_move: bool,
    is_touch: bool,
    threshold_pct: float = 2.0,
) -> dict:
    """
    ZERO_SCROLL split by device:
    - touch (mobile): no scroll (depth < threshold)
    - mouse (desktop): no scroll AND no mouse-move events
    """
    if is_touch:
        fired = scroll_depth_pct < threshold_pct
        reason = f"Mobile scroll depth {scroll_depth_pct:.1f}% < {threshold_pct}%"
    else:
        fired = scroll_depth_pct < threshold_pct and not has_mouse_move
        reason = (
            f"Desktop: depth {scroll_depth_pct:.1f}% < {threshold_pct}% "
            f"{'and no mouse-move → suspicious' if not has_mouse_move else 'but has mouse-move → OK'}"
        )
    return {
        "rule": "ZERO_SCROLL",
        "fired": fired,
        "scroll_depth_pct": scroll_depth_pct,
        "is_touch": is_touch,
        "reason": reason,
    }


# ── ALLOWLIST: Meta crawlers + in-app browsers ─────────────────────────────────

_CRAWLER_PATTERNS = [
    "facebookexternalhit",
    "facebookcatalog",
    "facebot",
    "linkedinbot",
    "twitterbot",
]
_INAPP_PATTERNS = ["FBAN", "FBAV", "Instagram", "TikTok"]


def is_allowlisted(user_agent: str) -> dict:
    """Meta crawlers and in-app browsers are never flagged as fraud."""
    ua_lower = user_agent.lower()
    for pattern in _CRAWLER_PATTERNS:
        if pattern in ua_lower:
            return {"allowlisted": True, "reason": f"Meta/social crawler: {pattern}"}
    for pattern in _INAPP_PATTERNS:
        if pattern in user_agent:
            return {"allowlisted": True, "reason": f"In-app browser: {pattern}"}
    return {"allowlisted": False, "reason": "Not allowlisted"}


# ── AGGREGATE L2 ──────────────────────────────────────────────────────────────

def run_layer2_checks(session_data: dict) -> dict:
    """
    Run all L2 checks. Returns is_suspicious, rules_fired, checks list.

    session_data keys:
      inter_event_gaps_ms, scroll_events, scroll_depth_pct, has_mouse_move,
      is_touch, ip_address, user_agent, viewport_w, viewport_h, recent_cluster_size
    """
    ua = session_data.get("user_agent", "")
    allowlist = is_allowlisted(ua)
    if allowlist["allowlisted"]:
        return {
            "is_suspicious": False,
            "rules_fired": [],
            "checks": [{"rule": "ALLOWLIST", "fired": False, "reason": allowlist["reason"]}],
        }
    checks = [
        check_automated_timing(session_data.get("inter_event_gaps_ms", [])),
        check_linear_scroll(
            session_data.get("scroll_events", []),
            is_touch=session_data.get("is_touch", False),
        ),
        check_ip_ua_viewport_cluster(
            session_data.get("ip_address", ""),
            ua,
            session_data.get("viewport_w", 0),
            session_data.get("viewport_h", 0),
            session_data.get("recent_cluster_size", 0),
        ),
        check_zero_scroll(
            session_data.get("scroll_depth_pct", 0.0),
            session_data.get("has_mouse_move", False),
            session_data.get("is_touch", False),
        ),
    ]
    rules_fired = [c["rule"] for c in checks if c.get("fired")]
    return {
        "is_suspicious": len(rules_fired) >= 1,
        "rules_fired": rules_fired,
        "checks": checks,
    }


# ── SHARE-OF-SUSPICIOUS ───────────────────────────────────────────────────────

def compute_suspicious_share(
    total_sessions: int,
    suspicious_sessions: int,
    source_breakdown: Optional[dict] = None,
) -> dict:
    """
    Share of suspicious sessions over a window.
    Alert threshold: > 10% triggers notification (vision §12.6.2 Layer 4).
    """
    if total_sessions == 0:
        return {"share_pct": 0.0, "alert": False, "reason": "No sessions in window"}
    share_pct = suspicious_sessions / total_sessions * 100
    alert = share_pct > 10.0
    return {
        "share_pct": round(share_pct, 2),
        "total_sessions": total_sessions,
        "suspicious_sessions": suspicious_sessions,
        "alert": alert,
        "source_breakdown": source_breakdown or {},
        "reason": f"{share_pct:.1f}% suspicious {'> 10% → ALERT' if alert else '≤ 10% → OK'}",
    }


# ── NIGHT_HOUR_BURST ──────────────────────────────────────────────────────────

def check_night_hour_burst(
    events: list[dict],
    burst_threshold: int = 10,
    window_minutes: int = 15,
) -> dict:
    """
    Night-hour click burst detection (vision §12.6.2).
    Night = 23:00–05:00 local time. Burst = burst_threshold+ events within window_minutes.
    Each event: {"occurred_at": "ISO8601", "tz_offset_hours": int (default 0)}.
    """
    from datetime import datetime, timedelta

    night_events = []
    for e in events:
        ts_str = e.get("occurred_at", "")
        try:
            ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
        except (ValueError, AttributeError):
            continue

        tz_offset  = int(e.get("tz_offset_hours", 0))
        local_hour = (ts.hour + tz_offset) % 24
        if local_hour >= 23 or local_hour < 5:
            night_events.append(ts)

    if len(night_events) < burst_threshold:
        return {"is_burst": False, "night_event_count": len(night_events)}

    night_events.sort()
    window = timedelta(minutes=window_minutes)
    max_in_window = 0
    for i, ts in enumerate(night_events):
        count = sum(1 for t in night_events[i:] if t - ts <= window)
        max_in_window = max(max_in_window, count)

    return {
        "is_burst":          max_in_window >= burst_threshold,
        "night_event_count": len(night_events),
        "max_in_window":     max_in_window,
        "window_minutes":    window_minutes,
    }
