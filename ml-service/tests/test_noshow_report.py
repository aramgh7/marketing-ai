"""Roadmap 8-15: show rate by risk tier, rolling-holdout AUC, slot fill — pure logic."""
from app.services.noshow_report_service import (
    AUC_GATE,
    build_report,
    compute_auc,
    rolling_holdout_auc,
    show_rate_by_tier,
    slot_fill_rate,
)


def _rows():
    rows = []
    # HIGH tier mostly no-shows, LOW tier mostly shows; scored 3 days before the appointment
    for i in range(40):
        rows.append({"noshow_prob": 0.7, "risk_tier": "HIGH", "outcome": "NO_SHOW" if i < 28 else "COMPLETED",
                     "scored_at": "2026-10-01T00:00:00Z", "scheduled_at": "2026-10-04T00:00:00Z"})
    for i in range(40):
        rows.append({"noshow_prob": 0.15, "risk_tier": "LOW", "outcome": "COMPLETED" if i < 34 else "NO_SHOW",
                     "scored_at": "2026-10-01T00:00:00Z", "scheduled_at": "2026-10-04T00:00:00Z"})
    rows.append({"noshow_prob": 0.4, "risk_tier": "MED", "outcome": None})   # unresolved → ignored
    return rows


def test_auc_perfect_random_and_degenerate():
    assert compute_auc([0.9, 0.8, 0.2, 0.1], [1, 1, 0, 0]) == 1.0
    assert compute_auc([0.1, 0.2, 0.8, 0.9], [1, 1, 0, 0]) == 0.0
    assert compute_auc([0.5, 0.5, 0.5, 0.5], [1, 0, 1, 0]) == 0.5
    assert compute_auc([0.5, 0.6], [1, 1]) is None


def test_show_rate_by_tier_with_intervals():
    tiers = {t["risk_tier"]: t for t in show_rate_by_tier(_rows())}
    assert tiers["HIGH"]["n"] == 40 and tiers["HIGH"]["show_rate"] == 0.3
    assert tiers["LOW"]["show_rate"] == 0.85 and tiers["LOW"]["ci_low"] < 0.85 < tiers["LOW"]["ci_high"]
    assert tiers["MED"]["n"] == 0 and tiers["MED"]["show_rate"] is None


def test_holdout_auc_passes_gate_on_separable_data():
    res = rolling_holdout_auc(_rows(), min_lead_days=1.0)
    assert res["n"] == 80 and res["auc"] >= AUC_GATE and res["passes_gate"] is True


def test_holdout_excludes_late_scores():
    rows = _rows()
    for r in rows:
        r["scored_at"] = "2026-10-03T23:00:00Z"        # scored 1 h before the call
    res = rolling_holdout_auc(rows, min_lead_days=1.0)
    assert res["n"] == 0 and res["auc"] is None and res["passes_gate"] is False


def test_slot_fill_rate_and_overflow():
    res = slot_fill_rate([
        {"slot_at": "2026-10-20T15:00:00Z", "capacity": 2, "booked": 3, "showed": 2},
        {"slot_at": "2026-10-20T16:00:00Z", "capacity": 2, "booked": 2, "showed": 1},
        {"slot_at": "2026-10-20T17:00:00Z", "capacity": 1, "booked": 2, "showed": 2},   # overbooked and both showed
    ])
    assert res["capacity"] == 5 and res["showed"] == 5 and res["fill_rate"] == 1.0
    assert res["booking_rate"] == 1.4 and res["overflow_slots"] == 1
    assert res["detail"][1]["fill"] == 0.5


def test_build_report_shape():
    rep = build_report(_rows(), [], min_lead_days=1.0)
    assert set(rep) == {"by_tier", "holdout_auc", "slot_fill", "n_rows"}
    assert rep["slot_fill"]["fill_rate"] is None
