import pytest
from app.services.fraud_l2_service import (
    check_automated_timing,
    check_linear_scroll,
    check_zero_scroll,
    check_ip_ua_viewport_cluster,
    is_allowlisted,
    run_layer2_checks,
    compute_suspicious_share,
)


# ── AUTOMATED_TIMING ─────────────────────────────────────────────────────────

def test_automated_timing_bot_fires():
    # constant 100ms gaps → σ=0 → fires
    result = check_automated_timing([100.0] * 10)
    assert result["fired"] is True
    assert result["sigma_ms"] == 0.0

def test_automated_timing_human_does_not_fire():
    # highly irregular human timing
    result = check_automated_timing([100, 250, 80, 310, 190, 420, 60, 280])
    assert result["fired"] is False
    assert result["sigma_ms"] >= 50.0

def test_automated_timing_insufficient_events():
    result = check_automated_timing([50.0, 50.0])
    assert result["fired"] is False
    assert "Insufficient" in result["reason"]

def test_automated_timing_exactly_min_events():
    result = check_automated_timing([100.0] * 5)
    assert result["fired"] is True


# ── LINEAR_SCROLL ─────────────────────────────────────────────────────────────

def _make_linear_scroll(n=6, step_ms=500, step_pct=5.0):
    return [{"ts_ms": i * step_ms, "depth_pct": i * step_pct} for i in range(1, n + 1)]

def _make_human_scroll():
    return [
        {"ts_ms": 100,  "depth_pct": 5.0},
        {"ts_ms": 800,  "depth_pct": 18.0},
        {"ts_ms": 1500, "depth_pct": 22.0},
        {"ts_ms": 3000, "depth_pct": 55.0},
        {"ts_ms": 4200, "depth_pct": 60.0},
        {"ts_ms": 6500, "depth_pct": 71.0},
    ]

def test_linear_scroll_bot_fires():
    events = _make_linear_scroll()
    result = check_linear_scroll(events)
    assert result["fired"] is True
    assert result["r2"] >= 0.98

def test_linear_scroll_human_does_not_fire():
    result = check_linear_scroll(_make_human_scroll())
    assert result["fired"] is False

def test_linear_scroll_insufficient_points():
    result = check_linear_scroll([{"ts_ms": 0, "depth_pct": 10}, {"ts_ms": 500, "depth_pct": 20}])
    assert result["fired"] is False
    assert "Insufficient" in result["reason"]

def test_linear_scroll_touch_device_type():
    result = check_linear_scroll(_make_linear_scroll(), is_touch=True)
    assert result["device_type"] == "touch"

def test_linear_scroll_mouse_device_type():
    result = check_linear_scroll(_make_linear_scroll(), is_touch=False)
    assert result["device_type"] == "mouse"


# ── ZERO_SCROLL ───────────────────────────────────────────────────────────────

def test_zero_scroll_mobile_no_scroll_fires():
    result = check_zero_scroll(scroll_depth_pct=0.5, has_mouse_move=False, is_touch=True)
    assert result["fired"] is True

def test_zero_scroll_mobile_with_scroll_no_fire():
    result = check_zero_scroll(scroll_depth_pct=30.0, has_mouse_move=False, is_touch=True)
    assert result["fired"] is False

def test_zero_scroll_desktop_no_move_fires():
    result = check_zero_scroll(scroll_depth_pct=0.5, has_mouse_move=False, is_touch=False)
    assert result["fired"] is True

def test_zero_scroll_desktop_has_move_no_fire():
    result = check_zero_scroll(scroll_depth_pct=0.5, has_mouse_move=True, is_touch=False)
    assert result["fired"] is False


# ── IP CLUSTER ────────────────────────────────────────────────────────────────

def test_ip_cluster_large_fires():
    # Vision §12.6.2: 50+ sessions with same IP+UA+viewport per hour = click farm
    result = check_ip_ua_viewport_cluster("1.2.3.4", "SomeBot/1.0", 1920, 1080, 55)
    assert result["fired"] is True
    assert result["cluster_size"] == 55

def test_ip_cluster_small_no_fire():
    result = check_ip_ua_viewport_cluster("1.2.3.4", "Mozilla/5.0", 1920, 1080, 3)
    assert result["fired"] is False


# ── ALLOWLIST ─────────────────────────────────────────────────────────────────

def test_allowlist_meta_crawler():
    result = is_allowlisted("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)")
    assert result["allowlisted"] is True

def test_allowlist_normal_user():
    result = is_allowlisted("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0")
    assert result["allowlisted"] is False

def test_allowlist_inapp_browser():
    result = is_allowlisted("Mozilla/5.0 [FBAN/FBIOS;FBDV/iPhone16,2]")
    assert result["allowlisted"] is True

def test_allowlist_instagram():
    result = is_allowlisted("Mozilla/5.0 Instagram 320.0.0.0 Android")
    assert result["allowlisted"] is True


# ── AGGREGATE run_layer2_checks ───────────────────────────────────────────────

def test_run_layer2_allowlisted_not_suspicious():
    session = {
        "user_agent": "facebookexternalhit/1.1",
        "inter_event_gaps_ms": [100.0] * 10,
        "scroll_events": _make_linear_scroll(),
    }
    result = run_layer2_checks(session)
    assert result["is_suspicious"] is False
    assert result["rules_fired"] == []

def test_run_layer2_automated_timing_fires():
    session = {
        "user_agent": "Mozilla/5.0 Chrome/120",
        "inter_event_gaps_ms": [100.0] * 10,
        "scroll_events": [],
        "scroll_depth_pct": 50.0,
        "has_mouse_move": True,
        "is_touch": False,
        "ip_address": "1.2.3.4",
        "viewport_w": 1920,
        "viewport_h": 1080,
        "recent_cluster_size": 0,
    }
    result = run_layer2_checks(session)
    assert result["is_suspicious"] is True
    assert "AUTOMATED_TIMING" in result["rules_fired"]

def test_run_layer2_clean_session_not_suspicious():
    session = {
        "user_agent": "Mozilla/5.0 Chrome/120",
        "inter_event_gaps_ms": [100, 250, 80, 310, 190, 420, 60, 280],
        "scroll_events": _make_human_scroll(),
        "scroll_depth_pct": 60.0,
        "has_mouse_move": True,
        "is_touch": False,
        "ip_address": "1.2.3.4",
        "viewport_w": 1920,
        "viewport_h": 1080,
        "recent_cluster_size": 1,
    }
    result = run_layer2_checks(session)
    assert result["is_suspicious"] is False


# ── SHARE-OF-SUSPICIOUS ───────────────────────────────────────────────────────

def test_share_above_10pct_alerts():
    result = compute_suspicious_share(100, 15)
    assert result["alert"] is True
    assert result["share_pct"] == 15.0

def test_share_exactly_10pct_no_alert():
    result = compute_suspicious_share(100, 10)
    assert result["alert"] is False  # > 10%, not >= 10%

def test_share_below_10pct_no_alert():
    result = compute_suspicious_share(100, 5)
    assert result["alert"] is False
    assert result["share_pct"] == 5.0

def test_share_zero_sessions_no_alert():
    result = compute_suspicious_share(0, 0)
    assert result["alert"] is False
    assert result["share_pct"] == 0.0

def test_share_with_breakdown():
    result = compute_suspicious_share(50, 10, {"AUTOMATED_TIMING": 7, "IP_UA_VIEWPORT_CLUSTER": 3})
    assert result["source_breakdown"]["AUTOMATED_TIMING"] == 7
