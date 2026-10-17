"""
Niche Benchmarks — first-party only (vision §8; arch §16.10).
Aggregate floor: ≥5 tenants per niche cell (vision §25 p.19).
Pure logic — zero FastAPI/Pydantic imports.
"""
from __future__ import annotations
from collections import defaultdict


AGGREGATE_FLOOR = 5


def compute_niche_benchmarks(
    tenant_funnel_data: list[dict],
    data_window_days: int = 30,
) -> list[dict]:
    """
    Computes benchmark percentiles per niche.
    Returns list of benchmark rows (value=None when tenant_count < AGGREGATE_FLOOR).

    Metrics: show_rate_p50, show_rate_p75, form_to_booked_p50, booking_distance_h_p50.
    Source: ONLY first-party events — never Meta Platform data (§25 p.19).
    """
    niche_data: dict[str, list[dict]] = defaultdict(list)
    for row in tenant_funnel_data:
        niche = row.get("niche_key") or "generic"
        niche_data[niche].append(row)

    benchmarks = []
    for niche_key, rows in niche_data.items():
        n = len(rows)
        if n < AGGREGATE_FLOOR:
            benchmarks.append({
                "niche_key":        niche_key,
                "metric_key":       "show_rate_p50",
                "value":            None,
                "tenant_count":     n,
                "data_window_days": data_window_days,
                "reason":           f"Below aggregate floor ({n} < {AGGREGATE_FLOOR})",
            })
            continue

        # Show rates
        show_rates = []
        for r in rows:
            booked = r.get("booked") or 0
            held   = r.get("held")   or 0
            if booked > 0:
                show_rates.append(held / booked)

        if show_rates:
            show_rates.sort()
            p50 = show_rates[min(int(len(show_rates) * 0.50), len(show_rates) - 1)]
            p75 = show_rates[min(int(len(show_rates) * 0.75), len(show_rates) - 1)]
            benchmarks.append({
                "niche_key": niche_key, "metric_key": "show_rate_p50",
                "value": round(p50, 4), "tenant_count": n, "data_window_days": data_window_days,
            })
            benchmarks.append({
                "niche_key": niche_key, "metric_key": "show_rate_p75",
                "value": round(p75, 4), "tenant_count": n, "data_window_days": data_window_days,
            })

        # Form-to-booked
        f2b_rates = []
        for r in rows:
            forms  = r.get("form_submits") or 0
            booked = r.get("booked")       or 0
            if forms > 0:
                f2b_rates.append(booked / forms)

        if f2b_rates:
            f2b_rates.sort()
            p50 = f2b_rates[min(int(len(f2b_rates) * 0.50), len(f2b_rates) - 1)]
            benchmarks.append({
                "niche_key": niche_key, "metric_key": "form_to_booked_p50",
                "value": round(p50, 4), "tenant_count": n, "data_window_days": data_window_days,
            })

        # Booking distance
        distances = [r.get("avg_booking_distance_h") for r in rows if r.get("avg_booking_distance_h") is not None]
        if distances:
            distances.sort()
            p50 = distances[min(int(len(distances) * 0.50), len(distances) - 1)]
            benchmarks.append({
                "niche_key": niche_key, "metric_key": "booking_distance_h_p50",
                "value": round(p50, 2), "tenant_count": n, "data_window_days": data_window_days,
            })

    return benchmarks


def compare_tenant_to_niche(
    tenant_metrics: dict,
    benchmarks: list[dict],
) -> dict:
    """
    Returns relative performance: above/below/at benchmark per metric.
    Only for visible benchmarks (value is not None).
    """
    result = {}
    bm_map = {b["metric_key"]: b["value"] for b in benchmarks if b.get("value") is not None}

    metric_map = {
        "show_rate_p50":          "show_rate",
        "form_to_booked_p50":     "form_to_booked",
        "booking_distance_h_p50": "avg_booking_distance_h",
    }

    for metric_key, bm_value in bm_map.items():
        tenant_field = metric_map.get(metric_key)
        if not tenant_field:
            continue
        tenant_value = tenant_metrics.get(tenant_field)
        if tenant_value is None:
            continue

        diff = float(tenant_value) - float(bm_value)
        result[metric_key] = {
            "tenant_value":    round(float(tenant_value), 4),
            "benchmark_value": round(float(bm_value), 4),
            "diff":            round(diff, 4),
            "performance":     "above" if diff > 0.02 else ("below" if diff < -0.02 else "at"),
        }

    return result
