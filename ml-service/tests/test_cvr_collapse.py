"""Roadmap 9-8: CVR-collapse detector — stable traffic, conversions → 0 within the last hour."""
from app.services.cvr_collapse_service import detect, detect_many


def _series(sessions=150, conv=6, last_sessions=150, last_conv=0, n=24):
    rows = [{"hour": f"h{i}", "sessions": sessions, "conversions": conv} for i in range(n - 1)]
    rows.append({"hour": "now", "sessions": last_sessions, "conversions": last_conv})
    return rows


def test_collapse_on_stable_traffic_zero_conversions():
    res = detect(_series())
    assert res["verdict"] == "COLLAPSE"
    assert res["traffic_stable"] is True
    assert res["cvr_last"] == 0.0 and res["cvr_mean_24h"] == 0.04


def test_ok_when_conversions_are_normal():
    assert detect(_series(last_conv=5))["verdict"] == "OK"
    # 10 % of the mean is the threshold: 0.4 conversions on 150 sessions → below; 1 → 0.0067 > 0.004
    assert detect(_series(last_conv=1))["verdict"] == "OK"


def test_traffic_drop_is_not_a_collapse():
    res = detect(_series(last_sessions=100, last_conv=0))
    assert res["verdict"] == "OK"
    assert res["traffic_stable"] is False
    assert "traffic changed" in res["reason"]


def test_min_sessions_and_baseline_guards():
    assert detect(_series(last_sessions=50, last_conv=0))["verdict"] == "INSUFFICIENT_DATA"
    assert detect(_series(conv=0, last_conv=0))["verdict"] == "INSUFFICIENT_DATA"
    assert detect(_series(n=4))["verdict"] == "INSUFFICIENT_DATA"
    assert detect([])["verdict"] == "INSUFFICIENT_DATA"


def test_ctr_traffic_metric_when_impressions_present():
    rows = [{"hour": i, "impressions": 10_000, "clicks": 200, "sessions": 180, "conversions": 9} for i in range(23)]
    rows.append({"hour": 23, "impressions": 10_000, "clicks": 200, "sessions": 180, "conversions": 0})
    res = detect(rows)
    assert res["verdict"] == "COLLAPSE" and res["traffic_last"] == 0.02
    rows[-1]["clicks"] = 100   # CTR halved → market/creative, not a collapse
    assert detect(rows)["verdict"] == "OK"


def test_detect_many_keeps_ids_and_order():
    out = detect_many([{"id": "a", "series": _series()}, {"id": "b", "series": _series(last_conv=6)}])
    assert [r["id"] for r in out] == ["a", "b"]
    assert [r["verdict"] for r in out] == ["COLLAPSE", "OK"]
    assert detect_many([{"id": "x"}])[0]["verdict"] == "INSUFFICIENT_DATA"
