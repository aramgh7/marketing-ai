import pytest
from datetime import datetime, timezone, timedelta
from app.services.attribution_service import (
    last_touch_attribution,
    linear_attribution,
    time_decay_attribution,
    data_driven_attribution,
    run_all_models,
    aggregate_channel_credit,
)


def make_tp(id_, channel, days_ago=0):
    dt = datetime.now(timezone.utc) - timedelta(days=days_ago)
    return {"id": id_, "channel": channel, "touched_at": dt.isoformat()}


def test_last_touch_single():
    tps = [make_tp("a", "PAID_SOCIAL")]
    result = last_touch_attribution(tps)
    assert len(result) == 1
    assert result[0]["credit"] == 1.0


def test_last_touch_multi():
    tps = [make_tp("a", "PAID_SOCIAL", days_ago=5), make_tp("b", "EMAIL", days_ago=2), make_tp("c", "DIRECT", days_ago=0)]
    result = last_touch_attribution(tps)
    credits = {r["touchpoint_id"]: r["credit"] for r in result}
    assert credits["c"] == 1.0
    assert credits["a"] == 0.0
    assert credits["b"] == 0.0


def test_linear_attribution():
    tps = [make_tp(str(i), "PAID_SOCIAL") for i in range(4)]
    result = linear_attribution(tps)
    assert len(result) == 4
    for r in result:
        assert r["credit"] == pytest.approx(0.25, abs=0.0001)
    assert sum(r["credit"] for r in result) == pytest.approx(1.0, abs=0.0001)


def test_time_decay_recent_higher():
    # 3 touchpoints: 30d ago, 7d ago, today
    tps = [
        make_tp("old",    "ORGANIC",     days_ago=30),
        make_tp("mid",    "EMAIL",        days_ago=7),
        make_tp("recent", "PAID_SOCIAL",  days_ago=0),
    ]
    result = time_decay_attribution(tps)
    credits = {r["touchpoint_id"]: r["credit"] for r in result}
    assert credits["recent"] > credits["mid"] > credits["old"]
    assert sum(credits.values()) == pytest.approx(1.0, abs=0.001)


def test_data_driven_paid_social_higher():
    tps = [make_tp("ps", "PAID_SOCIAL"), make_tp("d", "DIRECT")]
    result = data_driven_attribution(tps)
    credits = {r["touchpoint_id"]: r["credit"] for r in result}
    # PAID_SOCIAL weight=1.4, DIRECT weight=0.6 → ps gets 1.4/2.0 = 0.7
    assert credits["ps"] == pytest.approx(0.7, abs=0.001)
    assert credits["d"] == pytest.approx(0.3, abs=0.001)


def test_run_all_models_returns_four_keys():
    tps = [make_tp("a", "PAID_SOCIAL"), make_tp("b", "EMAIL")]
    result = run_all_models(tps)
    assert set(result.keys()) == {"last_touch", "linear", "time_decay", "data_driven"}


def test_channel_aggregate_paid_social_highest():
    tps = [
        make_tp("a", "PAID_SOCIAL"),
        make_tp("b", "PAID_SOCIAL"),
        make_tp("c", "EMAIL"),
    ]
    model_credits = linear_attribution(tps)
    agg = aggregate_channel_credit(model_credits, tps)
    assert list(agg.keys())[0] == "PAID_SOCIAL"
    assert agg["PAID_SOCIAL"] == pytest.approx(2 / 3, abs=0.001)


def test_empty_touchpoints():
    assert last_touch_attribution([]) == []
    assert linear_attribution([]) == []
    assert time_decay_attribution([]) == []
    assert data_driven_attribution([]) == []
