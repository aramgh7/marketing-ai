from datetime import datetime, timezone, timedelta
from app.services.fraud_l2_service import check_night_hour_burst


def _ts(hour: int, minute: int = 0) -> str:
    dt = datetime(2024, 6, 15, hour, minute, 0, tzinfo=timezone.utc)
    return dt.isoformat()


def test_night_burst_detected():
    # 15 events at 02:00 UTC within 15 min → is_burst
    events = [{"occurred_at": _ts(2, i % 15), "tz_offset_hours": 0} for i in range(15)]
    result = check_night_hour_burst(events, burst_threshold=10, window_minutes=15)
    assert result["is_burst"] is True
    assert result["night_event_count"] == 15


def test_night_burst_not_detected_daytime():
    # Events at 12:00 UTC → not night
    events = [{"occurred_at": _ts(12, i), "tz_offset_hours": 0} for i in range(20)]
    result = check_night_hour_burst(events, burst_threshold=10, window_minutes=15)
    assert result["is_burst"] is False
    assert result["night_event_count"] == 0


def test_night_burst_below_threshold():
    # 5 night events, threshold=10 → no burst
    events = [{"occurred_at": _ts(3, i), "tz_offset_hours": 0} for i in range(5)]
    result = check_night_hour_burst(events, burst_threshold=10, window_minutes=15)
    assert result["is_burst"] is False


def test_night_burst_tz_offset():
    # 14:00 UTC but tz_offset=+10 → local 00:00 → night
    events = [{"occurred_at": _ts(14, i), "tz_offset_hours": 10} for i in range(15)]
    result = check_night_hour_burst(events, burst_threshold=10, window_minutes=30)
    assert result["night_event_count"] == 15
