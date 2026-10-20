"""No-show reporting (roadmap 8-15; vision §19.18.2) — pure logic.

  show_rate_by_tier(rows)        show rate + 90 % Jeffreys interval per risk tier (LOW/MED/HIGH)
  rolling_holdout_auc(rows)      AUC of noshow_prob vs outcome on scores made before the outcome
                                 was known (holdout = scored ≥ `min_lead_days` before scheduled)
  slot_fill_rate(slots)          team-slot fill: showed / capacity per slot and overall
  compute_auc(scores, labels)    rank-based (Mann–Whitney) AUC, ties = 0.5

Row shape: {noshow_prob, risk_tier, outcome: COMPLETED|NO_SHOW, scored_at, scheduled_at}
Slot shape: {slot_at, capacity, booked, showed}
"""
from __future__ import annotations

import math
from datetime import datetime, timezone

AUC_GATE = 0.70
TIERS = ("LOW", "MED", "HIGH")


def _jeffreys_90(successes: int, n: int) -> tuple[float, float]:
    if n == 0:
        return 0.0, 1.0
    a, b = successes + 0.5, n - successes + 0.5
    mean = a / (a + b)
    sd = math.sqrt(a * b / ((a + b) ** 2 * (a + b + 1)))
    return max(0.0, round(mean - 1.6449 * sd, 4)), min(1.0, round(mean + 1.6449 * sd, 4))


def compute_auc(scores: list[float], labels: list[int]) -> float | None:
    """AUC for predicting label=1 from score (higher score = more likely 1). None without both classes."""
    pos = [s for s, y in zip(scores, labels) if y == 1]
    neg = [s for s, y in zip(scores, labels) if y == 0]
    if not pos or not neg:
        return None
    # rank-sum with tie handling
    allv = sorted((s, y) for s, y in zip(scores, labels))
    ranks: dict[float, float] = {}
    i = 0
    while i < len(allv):
        j = i
        while j + 1 < len(allv) and allv[j + 1][0] == allv[i][0]:
            j += 1
        avg_rank = (i + 1 + j + 1) / 2.0
        ranks[allv[i][0]] = avg_rank
        i = j + 1
    rank_sum_pos = sum(ranks[s] for s in pos)
    n_pos, n_neg = len(pos), len(neg)
    auc = (rank_sum_pos - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg)
    return round(auc, 4)


def _parse(ts) -> datetime | None:
    if ts is None:
        return None
    if isinstance(ts, datetime):
        return ts if ts.tzinfo else ts.replace(tzinfo=timezone.utc)
    try:
        dt = datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def show_rate_by_tier(rows: list[dict]) -> list[dict]:
    out = []
    for tier in TIERS:
        sub = [r for r in rows if r.get("risk_tier") == tier and r.get("outcome") in ("COMPLETED", "NO_SHOW")]
        n = len(sub)
        showed = sum(1 for r in sub if r.get("outcome") == "COMPLETED")
        low, high = _jeffreys_90(showed, n)
        mean_prob = round(sum(float(r.get("noshow_prob", 0)) for r in sub) / n, 4) if n else None
        out.append({"risk_tier": tier, "n": n, "showed": showed,
                    "show_rate": round(showed / n, 4) if n else None, "ci_low": low, "ci_high": high,
                    "predicted_noshow_mean": mean_prob})
    return out


def rolling_holdout_auc(rows: list[dict], min_lead_days: float = 0.0) -> dict:
    """AUC of noshow_prob predicting NO_SHOW, on scores produced at least `min_lead_days` before the
    appointment (so the model could not have seen the outcome). The gate is AUC ≥ 0.70 (8-15)."""
    scores, labels = [], []
    for r in rows:
        if r.get("outcome") not in ("COMPLETED", "NO_SHOW"):
            continue
        scored, sched = _parse(r.get("scored_at")), _parse(r.get("scheduled_at"))
        if scored and sched and (sched - scored).total_seconds() < min_lead_days * 86400:
            continue
        scores.append(float(r.get("noshow_prob", 0.0)))
        labels.append(1 if r.get("outcome") == "NO_SHOW" else 0)
    auc = compute_auc(scores, labels)
    return {"auc": auc, "n": len(scores), "n_noshow": sum(labels), "gate": AUC_GATE,
            "passes_gate": auc is not None and auc >= AUC_GATE and len(scores) >= 30,
            "note": None if len(scores) >= 30 else "need >= 30 resolved scored appointments"}


def slot_fill_rate(slots: list[dict]) -> dict:
    """Per-slot and overall fill (showed / capacity) plus overbooking outcome (showed > capacity)."""
    detail = []
    cap_total = booked_total = showed_total = over = 0
    for s in slots:
        cap = int(s.get("capacity", 1) or 1)
        booked = int(s.get("booked", 0) or 0)
        showed = int(s.get("showed", 0) or 0)
        cap_total += cap
        booked_total += booked
        showed_total += showed
        if showed > cap:
            over += 1
        detail.append({"slot_at": s.get("slot_at"), "capacity": cap, "booked": booked, "showed": showed,
                       "fill": round(min(1.0, showed / cap), 4) if cap else None})
    return {"slots": len(slots), "capacity": cap_total, "booked": booked_total, "showed": showed_total,
            "fill_rate": round(showed_total / cap_total, 4) if cap_total else None,
            "booking_rate": round(booked_total / cap_total, 4) if cap_total else None,
            "overflow_slots": over, "detail": detail[:50]}


def build_report(rows: list[dict], slots: list[dict], min_lead_days: float = 0.0) -> dict:
    return {"by_tier": show_rate_by_tier(rows), "holdout_auc": rolling_holdout_auc(rows, min_lead_days),
            "slot_fill": slot_fill_rate(slots), "n_rows": len(rows)}
