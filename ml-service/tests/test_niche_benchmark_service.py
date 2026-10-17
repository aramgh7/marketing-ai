import pytest
from app.services.niche_benchmark_service import compute_niche_benchmarks, compare_tenant_to_niche, AGGREGATE_FLOOR


def make_tenant(niche, booked, held, forms, dist=None):
    return {
        "tenant_id":              f"t-{niche}-{booked}",
        "niche_key":              niche,
        "booked":                 booked,
        "held":                   held,
        "form_submits":           forms,
        "avg_booking_distance_h": dist,
    }


def test_below_aggregate_floor():
    data = [make_tenant("medtech", 10, 5, 20) for _ in range(3)]
    results = compute_niche_benchmarks(data)
    bm = next((r for r in results if r["niche_key"] == "medtech"), None)
    assert bm is not None
    assert bm["value"] is None
    assert bm["tenant_count"] == 3


def test_above_floor_returns_value():
    data = [make_tenant("saas", 10, 7, 20) for _ in range(6)]
    results = compute_niche_benchmarks(data)
    show_rate = next(
        (r for r in results if r["niche_key"] == "saas" and r["metric_key"] == "show_rate_p50"),
        None,
    )
    assert show_rate is not None
    assert show_rate["value"] is not None
    assert show_rate["tenant_count"] == 6


def test_show_rate_p50_correct():
    # 6 tenants with show_rates: 0.3, 0.4, 0.5, 0.6, 0.7, 0.8
    # p50 index = int(6*0.5) = 3 → 0.6
    data = [
        make_tenant("investor", 10, 3, 20),
        make_tenant("investor", 10, 4, 20),
        make_tenant("investor", 10, 5, 20),
        make_tenant("investor", 10, 6, 20),
        make_tenant("investor", 10, 7, 20),
        make_tenant("investor", 10, 8, 20),
    ]
    results = compute_niche_benchmarks(data)
    bm = next(
        (r for r in results if r["niche_key"] == "investor" and r["metric_key"] == "show_rate_p50"),
        None,
    )
    assert bm is not None
    assert bm["value"] == pytest.approx(0.6, abs=0.01)


def test_compare_tenant_above():
    benchmarks = [{"metric_key": "show_rate_p50", "value": 0.50}]
    result = compare_tenant_to_niche({"show_rate": 0.80}, benchmarks)
    assert result["show_rate_p50"]["performance"] == "above"
    assert result["show_rate_p50"]["diff"] == pytest.approx(0.30, abs=0.001)


def test_compare_tenant_below():
    benchmarks = [{"metric_key": "show_rate_p50", "value": 0.50}]
    result = compare_tenant_to_niche({"show_rate": 0.20}, benchmarks)
    assert result["show_rate_p50"]["performance"] == "below"


def test_compare_tenant_at():
    benchmarks = [{"metric_key": "show_rate_p50", "value": 0.50}]
    result = compare_tenant_to_niche({"show_rate": 0.51}, benchmarks)
    assert result["show_rate_p50"]["performance"] == "at"


def test_null_benchmark_excluded_from_compare():
    # value=None should be ignored
    benchmarks = [{"metric_key": "show_rate_p50", "value": None}]
    result = compare_tenant_to_niche({"show_rate": 0.80}, benchmarks)
    assert "show_rate_p50" not in result


def test_booking_distance_benchmark():
    data = [make_tenant("saas", 10, 7, 20, dist=float(h)) for h in [24, 36, 48, 72, 96, 120]]
    results = compute_niche_benchmarks(data)
    dist_bm = next(
        (r for r in results if r["niche_key"] == "saas" and r["metric_key"] == "booking_distance_h_p50"),
        None,
    )
    assert dist_bm is not None
    assert dist_bm["value"] is not None
